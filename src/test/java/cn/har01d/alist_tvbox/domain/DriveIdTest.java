package cn.har01d.alist_tvbox.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriveIdTest {

    @Test
    void parsesDriveIdentifiersAndLegacyNumericIds() {
        assertThat(DriveId.toType("quark")).isEqualTo(5);
        assertThat(DriveId.toType("baidu")).isEqualTo(10);
        assertThat(DriveId.toType("115")).isEqualTo(8);
        assertThat(DriveId.toType("local")).isEqualTo(4);
        assertThat(DriveId.toType("5")).isEqualTo(5);
        assertThat(DriveId.toType("10")).isEqualTo(10);
    }

    @Test
    void returnsCanonicalDriveIdentifierForExport() {
        assertThat(DriveId.toDrive(0)).isEqualTo("ali");
        assertThat(DriveId.toDrive(5)).isEqualTo("quark");
        assertThat(DriveId.toDrive(10)).isEqualTo("baidu");
        assertThat(DriveId.toDrive(12)).isEqualTo("duck");
    }

    @Test
    void normalizesLegacyNumericIdsToCanonicalDriveIdentifiers() {
        assertThat(DriveId.normalize("5")).isEqualTo("quark");
        assertThat(DriveId.normalize("quark")).isEqualTo("quark");
    }

    @Test
    void recognizesDriveAndLegacyNumericShareTokenNames() {
        assertThat(DriveId.isShareTokenName("quark@share-id@pwd")).isTrue();
        assertThat(DriveId.isShareTokenName("5@share-id@pwd")).isTrue();
        assertThat(DriveId.isShareTokenName("movie-name")).isFalse();
    }

    @Test
    void rejectsUnknownIdentifiers() {
        assertThatThrownBy(() -> DriveId.toType("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }
}
