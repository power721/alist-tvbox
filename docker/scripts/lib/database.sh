#!/bin/sh
# 数据库操作 - H2升级和恢复

# 升级 H2 数据库
upgrade_h2() {
  if [ -f /data/h2.version.txt ]; then
    log_info "H2 database already upgraded"
    return 0
  fi

  log_info "Attempting to upgrade H2 database"

  # 确定数据库文件位置
  file=/opt/atv/data/data
  [ -f /data/atv.mv.db ] && file=/data/atv

  log_info "Exporting database from: $file"

  # 导出数据库
  if /jre/bin/java -cp /h2-2.1.214.jar org.h2.tools.Script \
    -url jdbc:h2:file:$file \
    -user sa -password password \
    -script backup.sql; then

    log_info "Importing database with new H2 version"

    # 删除旧数据库文件
    rm -f ${file}.mv.db ${file}.trace.db

    # 导入到新版本
    if /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript \
      -url jdbc:h2:file:$file \
      -user sa -password password \
      -script backup.sql; then

      echo "2.3.232" > /data/h2.version.txt
      log_info "H2 database upgraded to 2.3.232"
      rm -f backup.sql  # Clean up backup
      return 0
    else
      log_error "Failed to import database"
      log_error "Backup SQL remains at ./backup.sql for manual recovery"
      return 1
    fi
  else
    log_error "Failed to export database"
    return 1
  fi
}

# 从备份恢复数据库
# ①先用 unzip 解出 SQL 再以纯文本导入:H2 RunScript 的 COMPRESSION ZIP 模式硬编码只认
#   zip 内名为 script.sql 的条目,旧版备份的条目名是临时文件名(atv-backup-*.sql),直接喂
#   给 RunScript 必报 "File not found: script.sql in ..."——此前「反复删库重建却恢复不
#   生效」的首要根因。解压路线对两种产物(H2 原生 script.sql / 旧版临时名)都兼容。
# ②旧库移为 atv.mv.db.bak 而非直接删除:导入失败时回滚原库继续启动。
# ③失败的恢复包改名为 database.zip.failed 隔离:否则 set -e 下容器每次重启都重复
#   「删库→重建→失败」死循环;人工确认后改回原名即可重试。
restore_database() {
  if [ -f "/data/database.zip" ]; then
    log_info "Restoring database from backup"

    rm -rf /tmp/db-restore
    mkdir -p /tmp/db-restore
    sql_file=""
    if unzip -q -o /data/database.zip -d /tmp/db-restore; then
      sql_file=$(ls /tmp/db-restore/script.sql /tmp/db-restore/*.sql 2>/dev/null | head -n 1)
    fi
    if [ -z "$sql_file" ]; then
      log_error "Failed to extract a .sql entry from database.zip"
      rm -rf /tmp/db-restore
      mv -f /data/database.zip /data/database.zip.failed
      log_warn "Restore package moved to /data/database.zip.failed; rename it back to retry"
      return 0
    fi

    if [ -f /data/atv.mv.db ]; then
      mv -f /data/atv.mv.db /data/atv.mv.db.bak
      log_info "Previous database kept as atv.mv.db.bak"
    fi
    rm -f /data/atv.trace.db

    if /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript \
      -url jdbc:h2:/data/atv \
      -user sa -password password \
      -script "$sql_file"; then

      rm -f /data/database.zip /data/atv.mv.db.bak
      rm -rf /tmp/db-restore
      log_info "Database restored successfully"
    else
      log_error "Failed to restore database"
      rm -f /data/atv.mv.db
      if [ -f /data/atv.mv.db.bak ]; then
        mv -f /data/atv.mv.db.bak /data/atv.mv.db
        log_info "Rolled back to previous database"
      fi
      mv -f /data/database.zip /data/database.zip.failed
      rm -rf /tmp/db-restore
      log_warn "Restore package moved to /data/database.zip.failed; rename it back to retry"
    fi
  fi
  return 0
}
