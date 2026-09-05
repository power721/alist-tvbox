package cn.har01d.alist_tvbox.config;

import db.migration.current.V2__Normalize_reserved_columns;
import db.migration.current.V3__Fix_null_sort_order;
import db.migration.current.V8__Normalize_enum_columns;
import db.migration.current.V10__PlaybackSync;
import db.migration.current.V11__PlaybackChangeSequence;
import db.migration.current.V12__WidenPlaybackVodId;
import db.migration.current.V13__PlaybackSourceName;
import db.migration.current.V14__PlaybackSelectionContext;
import db.migration.current.V15__PlaybackSyncScope;
import db.migration.current.V16__MigrateLegacyHistory;
import db.migration.current.V17__FixChangeSequenceWatermark;
import db.migration.current.V18__PlaybackDrivePath;
import db.migration.current.V19__LiveFollow;
import db.migration.current.V20__MediaSubscription;
import db.migration.current.V21__MediaSubscriptionMeta;
import db.migration.current.V22__MediaSubscriptionMetaFix;
import db.migration.current.V23__MediaSubscriptionAccounts;
import db.migration.current.V24__MediaSubscriptionBrokenEpisodes;
import db.migration.current.V25__MediaSubscriptionSchedule;
import db.migration.current.V26__MediaSubscriptionCrossDrive;
import db.migration.current.V27__MediaSubscriptionAliases;
import db.migration.current.V28__MediaSubscriptionMainDrives;
import db.migration.current.V29__MediaSubscriptionMaxEpisode;
import db.migration.current.V30__MediaSubscriptionEpisodeSource;
import db.migration.current.V31__MediaSubscriptionCover;
import db.migration.current.V32__MediaMetadata;
import db.migration.current.V33__MediaSubscriptionCaughtUp;
import db.migration.current.V34__MediaSubscriptionResourceLinkHash;
import db.migration.current.V35__MediaSubscriptionResourcePinned;
import db.migration.current.V36__MediaSubscriptionNotify;
import db.migration.current.V37__AccountOwnership;
import db.migration.current.V38__SubscriptionOwnership;
import db.migration.current.V39__UserVodSecret;
import db.migration.current.V40__PlayUrlOwnership;
import db.migration.current.V41__MediaSubscriptionAirClock;
import db.migration.current.V42__MediaSubscriptionSeasonStart;
import db.migration.current.V43__MediaSubscriptionResourceStart;
import db.migration.current.V44__MediaSubscriptionResourceSeasonStarts;
import db.migration.current.V45__MovieDiffLog;
import db.migration.current.V46__MediaSubscriptionManualTotal;
import db.migration.current.V47__MediaSubscriptionResourceFailKind;
import db.migration.current.V48__MediaSubscriptionMagnetOffline;
import db.migration.current.V49__OfflineDownloadTaskMagnetQuota;
import db.migration.current.V50__MediaSubscriptionCustomKeywords;
import db.migration.current.V51__MediaSubscriptionAirWeekdays;
import db.migration.current.V52__MediaSubscriptionEpisodeFallback;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NativeDetector;

/**
 * Flyway discovers SQL migrations fine in a GraalVM native image (Spring Boot's
 * NativeImageResourceProvider serves them as resources), but Java migrations are found
 * by classpath package scanning (Flyway's ClassPathScanner), which native-image cannot
 * perform: directory listing of {@code resource:} paths is unsupported, so the scanner
 * logs "Unable to scan location: /db/migration/current (unsupported protocol: resource)"
 * and returns nothing. As a result the Java migrations under db.migration.current
 * are never discovered and never run natively (leaving the schema half-migrated).
 *
 * Spring Boot collects {@code JavaMigration} beans for Flyway, but these cannot be
 * registered as unconditional beans: in a regular JVM build Flyway still scans the
 * classpath and would then see two migrations with the same version ("Found more than
 * one migration with version ..."). So instead we add them through a
 * FlywayConfigurationCustomizer, and only when actually running in a native image
 * (detected at runtime via {@link NativeDetector}).
 */
@Configuration
public class NativeFlywayMigrationConfig {

    @Bean
    public FlywayConfigurationCustomizer nativeJavaMigrationCustomizer() {
        return configuration -> {
            if (!NativeDetector.inNativeImage()) {
                return;
            }
            configuration.javaMigrations(
                    new V2__Normalize_reserved_columns(),
                    new V3__Fix_null_sort_order(),
                    new V8__Normalize_enum_columns(),
                    new V10__PlaybackSync(),
                    new V11__PlaybackChangeSequence(),
                    new V12__WidenPlaybackVodId(),
                    new V13__PlaybackSourceName(),
                    new V14__PlaybackSelectionContext(),
                    new V15__PlaybackSyncScope(),
                    new V16__MigrateLegacyHistory(),
                    new V17__FixChangeSequenceWatermark(),
                    new V18__PlaybackDrivePath(),
                    new V19__LiveFollow(),
                    new V20__MediaSubscription(),
                    new V21__MediaSubscriptionMeta(),
                    new V22__MediaSubscriptionMetaFix(),
                    new V23__MediaSubscriptionAccounts(),
                    new V24__MediaSubscriptionBrokenEpisodes(),
                    new V25__MediaSubscriptionSchedule(),
                    new V26__MediaSubscriptionCrossDrive(),
                    new V27__MediaSubscriptionAliases(),
                    new V28__MediaSubscriptionMainDrives(),
                    new V29__MediaSubscriptionMaxEpisode(),
                    new V30__MediaSubscriptionEpisodeSource(),
                    new V31__MediaSubscriptionCover(),
                    new V32__MediaMetadata(),
                    new V33__MediaSubscriptionCaughtUp(),
                    new V34__MediaSubscriptionResourceLinkHash(),
                    new V35__MediaSubscriptionResourcePinned(),
                    new V36__MediaSubscriptionNotify(),
                    new V37__AccountOwnership(),
                    new V38__SubscriptionOwnership(),
                    new V39__UserVodSecret(),
                    new V40__PlayUrlOwnership(),
                    new V41__MediaSubscriptionAirClock(),
                    new V42__MediaSubscriptionSeasonStart(),
                    new V43__MediaSubscriptionResourceStart(),
                    new V44__MediaSubscriptionResourceSeasonStarts(),
                    new V45__MovieDiffLog(),
                    new V46__MediaSubscriptionManualTotal(),
                    new V47__MediaSubscriptionResourceFailKind(),
                    new V48__MediaSubscriptionMagnetOffline(),
                    new V49__OfflineDownloadTaskMagnetQuota(),
                    new V50__MediaSubscriptionCustomKeywords(),
                    new V51__MediaSubscriptionAirWeekdays(),
                    new V52__MediaSubscriptionEpisodeFallback());
        };
    }
}
