package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic backup handler for a single repository-backed entity. One instance is registered per
 * included entity in {@link BackupModuleRegistry}. It converts entities to/from plain maps with
 * Jackson so the manifest stays database-independent.
 */
public class BackupModuleHandler<T> {

    public enum IdStrategy {
        /** {@code @Id} without generation – the id is a plain column, preserved by save(). */
        ASSIGNED,
        /** {@code GenerationType.TABLE} – id is preserved by save(); id_generator is rebuilt afterwards. */
        TABLE,
        /** {@code GenerationType.IDENTITY} – DB auto-increment; ids cannot be forced via JPA. */
        IDENTITY
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String moduleName;
    private final String tableName;
    private final Class<T> entityClass;
    private final JpaRepository<T, ?> repository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final IdStrategy idStrategy;
    private final String keyField;

    public BackupModuleHandler(String moduleName,
                               String tableName,
                               Class<T> entityClass,
                               JpaRepository<T, ?> repository,
                               ObjectMapper objectMapper,
                               EntityManager entityManager,
                               IdStrategy idStrategy,
                               String keyField) {
        this.moduleName = moduleName;
        this.tableName = tableName;
        this.entityClass = entityClass;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.idStrategy = idStrategy;
        this.keyField = keyField;
    }

    public String moduleName() {
        return moduleName;
    }

    public String entityName() {
        return entityClass.getSimpleName();
    }

    public String tableName() {
        return tableName;
    }

    public IdStrategy idStrategy() {
        return idStrategy;
    }

    public List<Map<String, Object>> exportItems() {
        return repository.findAll().stream()
            .map(e -> objectMapper.convertValue(e, MAP_TYPE))
            .collect(Collectors.toList());
    }

    public void deleteAll() {
        repository.deleteAll();
    }

    public BackupRestoreResult restore(List<Map<String, Object>> items, BackupRestoreMode mode) {
        BackupRestoreResult result = new BackupRestoreResult();
        if (items == null || items.isEmpty()) {
            // In OVERWRITE the table was already cleared by the service (non-IDENTITY) or is handled
            // below (IDENTITY) – nothing to insert.
            if (mode == BackupRestoreMode.OVERWRITE && idStrategy == IdStrategy.IDENTITY) {
                deleteAll();
            }
            return result;
        }

        if (idStrategy == IdStrategy.IDENTITY) {
            // IDENTITY ids cannot be forced via JPA, and resetting the auto-increment requires DDL
            // (which would auto-commit and break the surrounding transaction). So we upsert by id:
            // existing rows are updated in place (preserving their id, e.g. admin user = 1) and, in
            // OVERWRITE mode, rows absent from the backup are removed.
            return restoreIdentity(items, mode, result);
        }

        if (mode == BackupRestoreMode.MERGE) {
            Map<Object, Object> idByKey = repository.findAll().stream()
                .map(e -> objectMapper.convertValue(e, MAP_TYPE))
                .filter(m -> m.get(keyField) != null && m.get("id") != null)
                .collect(Collectors.toMap(m -> m.get(keyField), m -> m.get("id"), (a, b) -> a));
            for (Map<String, Object> item : items) {
                Object key = item.get(keyField);
                Object existingId = key == null ? null : idByKey.get(key);
                if (existingId != null) {
                    // Existing row matched by business key: update in place via merge.
                    item.put("id", existingId);
                    repository.save(toEntity(item));
                    result.setUpdated(result.getUpdated() + 1);
                } else if (idStrategy == IdStrategy.TABLE) {
                    // New row, generated id: insert via the persister (merge would stale on a
                    // detached generated-id entity that has no row yet).
                    insertWithId(toEntity(item));
                    result.setCreated(result.getCreated() + 1);
                } else {
                    // ASSIGNED id (e.g. Setting keyed by name): merge upserts by the assigned id –
                    // it SELECTs first, so it updates an existing row or inserts a new one safely.
                    repository.save(toEntity(item));
                    result.setCreated(result.getCreated() + 1);
                }
            }
        } else {
            // OVERWRITE: the table was already cleared by the service. Insert each row with its
            // preserved id via Hibernate's low-level persister, which writes the given identifier
            // directly (bypassing the TABLE generator) and binds values with the entity's own
            // mapping (columns, enums, instants, relations). merge() would resolve a detached
            // generated-id entity to a stale UPDATE, and persist() rejects a preset id.
            for (Map<String, Object> item : items) {
                insertWithId(toEntity(item));
            }
            result.setCreated(items.size());
        }
        return result;
    }

    /** Insert a row with the entity's current (preserved) identifier, bypassing id generation. */
    private void insertWithId(T entity) {
        SharedSessionContractImplementor session = entityManager.unwrap(SharedSessionContractImplementor.class);
        EntityPersister persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(entityClass);
        Object id = persister.getIdentifier(entity, session);
        Object[] state = persister.getValues(entity);
        persister.getInsertCoordinator().insert(entity, id, state, session);
    }

    private BackupRestoreResult restoreIdentity(List<Map<String, Object>> items, BackupRestoreMode mode,
                                                BackupRestoreResult result) {
        List<Map<String, Object>> existing = repository.findAll().stream()
            .map(e -> objectMapper.convertValue(e, MAP_TYPE))
            .collect(Collectors.toList());
        Map<Object, Object> idByKey = existing.stream()
            .filter(m -> m.get(keyField) != null && m.get("id") != null)
            .collect(Collectors.toMap(m -> m.get(keyField), m -> m.get("id"), (a, b) -> a));
        Set<Long> existingIds = existing.stream()
            .map(m -> asLong(m.get("id")))
            .collect(Collectors.toSet());
        Set<Long> backupIds = new HashSet<>();

        // Insert in ascending-id order for determinism.
        List<Map<String, Object>> ordered = items.stream()
            .sorted(Comparator.comparing(m -> asLong(m.get("id"))))
            .collect(Collectors.toList());

        for (Map<String, Object> item : ordered) {
            Object key = item.get(keyField);
            Object matchedId = null;
            if ("id".equals(keyField)) {
                Long idValue = asLong(item.get("id"));
                if (idValue != Long.MAX_VALUE && existingIds.contains(idValue)) {
                    matchedId = item.get("id");
                }
            } else if (key != null) {
                matchedId = idByKey.get(key);
            }

            if (matchedId != null) {
                item.put("id", matchedId);
                repository.save(toEntity(item));
                backupIds.add(asLong(matchedId));
                result.setUpdated(result.getUpdated() + 1);
            } else {
                // New IDENTITY row: clear the id so JPA persists it with a fresh auto-increment
                // value (merge of a detached, absent id would stale; the id is DB-assigned anyway).
                item.put("id", null);
                T saved = repository.save(toEntity(item));
                Long savedId = asLong(objectMapper.convertValue(saved, MAP_TYPE).get("id"));
                backupIds.add(savedId);
                result.setCreated(result.getCreated() + 1);
            }
        }

        if (mode == BackupRestoreMode.OVERWRITE) {
            List<T> toDelete = repository.findAll().stream()
                .filter(e -> !backupIds.contains(asLong(objectMapper.convertValue(e, MAP_TYPE).get("id"))))
                .collect(Collectors.toList());
            if (!toDelete.isEmpty()) {
                repository.deleteAll(toDelete);
                result.setDeleted(toDelete.size());
            }
        }
        return result;
    }

    private T toEntity(Map<String, Object> item) {
        return objectMapper.convertValue(item, entityClass);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(value.toString());
    }
}
