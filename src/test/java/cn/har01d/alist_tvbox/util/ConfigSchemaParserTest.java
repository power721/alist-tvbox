package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.model.PluginFilterConfigField;
import cn.har01d.alist_tvbox.model.PluginFilterConfigSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigSchemaParserTest {

    private static final String PLUGIN_SCHEMA_PY = """
            # coding=utf-8
            import json

            PLUGIN_CONFIG_SCHEMA = {
              "description": "观影配置",
              "allowAdditional": false,
              "fields": [
                {"key": "sites", "label": "站点", "type": "string", "required": true, "aliases": ["site", "host"]},
                {"key": "username", "label": "用户名", "type": "string"},
                {"key": "password", "label": "密码", "type": "secret"},
                {"key": "cookie", "label": "Cookie", "type": "secret", "placeholder": "可代替账号密码", "defaultValue": ""}
              ]
            }

            class Spider(BaseSpider):
                def init(self, extend=""):
                    pass
            """;

    @Test
    void pluginConfigSchemaConstantIsParsed() {
        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(PLUGIN_SCHEMA_PY, "PLUGIN_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getDescription()).isEqualTo("观影配置");
        assertThat(schema.isAllowAdditional()).isFalse();
        assertThat(schema.getFields()).hasSize(4);

        PluginFilterConfigField site = schema.getFields().get(0);
        assertThat(site.getKey()).isEqualTo("sites");
        assertThat(site.getLabel()).isEqualTo("站点");
        assertThat(site.getType()).isEqualTo("string");
        assertThat(site.isRequired()).isTrue();
        assertThat(site.getAliases()).containsExactly("site", "host");

        PluginFilterConfigField password = schema.getFields().get(2);
        assertThat(password.getKey()).isEqualTo("password");
        assertThat(password.getType()).isEqualTo("secret");

        PluginFilterConfigField cookie = schema.getFields().get(3);
        assertThat(cookie.getType()).isEqualTo("secret");
        assertThat(cookie.getPlaceholder()).isEqualTo("可代替账号密码");
    }

    @Test
    void filterConfigSchemaConstantStillWorks() {
        String py = """
                FILTER_CONFIG_SCHEMA = {
                  "fields": [
                    {"key": "cookie", "type": "string"}
                  ]
                }

                class Spider(BaseSpider):
                    pass
                """;

        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(py, "FILTER_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getFields()).hasSize(1);
        assertThat(schema.getFields().get(0).getKey()).isEqualTo("cookie");
    }

    @Test
    void legacyCommentFormIsParsed() {
        String content = "// @config-schema {\"description\":\"legacy\",\"fields\":[{\"key\":\"site\",\"label\":\"站点\",\"type\":\"string\"}]}";

        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(content, "PLUGIN_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getDescription()).isEqualTo("legacy");
        assertThat(schema.getFields()).hasSize(1);
        assertThat(schema.getFields().get(0).getKey()).isEqualTo("site");
        assertThat(schema.getFields().get(0).getLabel()).isEqualTo("站点");
    }

    @Test
    void secspiderHeaderFormIsParsed() {
        // 模拟 secspider 加密 .txt：源码在加密 payload 里不可读，schema 以明文 //@config-schema:{...} 头导出。
        String content = String.join("\n",
                "//@name:观影",
                "//@version:3",
                "//@config-schema:{\"description\":\"观影配置\",\"fields\":["
                        + "{\"key\":\"sites\",\"label\":\"站点\",\"type\":\"string\"},"
                        + "{\"key\":\"password\",\"label\":\"密码\",\"type\":\"secret\"}]}",
                "//@format:secspider/1",
                "//@sig:base64:fake",
                "",
                "payload.base64:QUVT");

        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(content, "PLUGIN_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getDescription()).isEqualTo("观影配置");
        assertThat(schema.getFields()).hasSize(2);
        assertThat(schema.getFields().get(0).getKey()).isEqualTo("sites");
        assertThat(schema.getFields().get(1).getKey()).isEqualTo("password");
        assertThat(schema.getFields().get(1).getType()).isEqualTo("secret");
    }

    @Test
    void noDeclarationReturnsNull() {
        String py = """
                # coding=utf-8
                class Spider(BaseSpider):
                    def init(self, extend=""):
                        pass
                """;

        assertThat(ConfigSchemaParser.parse(py, "PLUGIN_CONFIG_SCHEMA")).isNull();
    }

    @Test
    void blankContentReturnsNull() {
        assertThat(ConfigSchemaParser.parse("", "PLUGIN_CONFIG_SCHEMA")).isNull();
        assertThat(ConfigSchemaParser.parse(null, "PLUGIN_CONFIG_SCHEMA")).isNull();
    }

    @Test
    void nestedObjectChildrenAreParsed() {
        String py = """
                PLUGIN_CONFIG_SCHEMA = {
                  "fields": [
                    {"key": "headers", "type": "object", "children": [
                      {"key": "ua", "type": "string"},
                      {"key": "referer", "type": "string"}
                    ]}
                  ]
                }

                class Spider(BaseSpider):
                  pass
                """;

        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(py, "PLUGIN_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getFields()).hasSize(1);
        PluginFilterConfigField headers = schema.getFields().get(0);
        assertThat(headers.getType()).isEqualTo("object");
        assertThat(headers.getChildren()).hasSize(2);
        assertThat(headers.getChildren().get(0).getKey()).isEqualTo("ua");
        assertThat(headers.getChildren().get(1).getKey()).isEqualTo("referer");
    }

    @Test
    void listFieldsWithItemSchemaAreParsed() {
        String py = """
                PLUGIN_CONFIG_SCHEMA = {
                  "fields": [
                    {"key": "channels", "label": "自定义频道", "type": "list", "itemLabel": "频道", "children": [
                      {"key": "name", "label": "名称", "type": "string"},
                      {"key": "id", "label": "频道ID", "type": "string"},
                      {"key": "url", "label": "频道链接", "type": "string"}
                    ]}
                  ]
                }

                class Spider(BaseSpider):
                  pass
                """;

        PluginFilterConfigSchema schema = ConfigSchemaParser.parse(py, "PLUGIN_CONFIG_SCHEMA");

        assertThat(schema).isNotNull();
        assertThat(schema.getFields()).hasSize(1);
        PluginFilterConfigField channels = schema.getFields().get(0);
        assertThat(channels.getType()).isEqualTo("list");
        assertThat(channels.getLabel()).isEqualTo("自定义频道");
        assertThat(channels.getItemLabel()).isEqualTo("频道");
        assertThat(channels.getChildren()).hasSize(3);
        assertThat(channels.getChildren().get(0).getKey()).isEqualTo("name");
        assertThat(channels.getChildren().get(1).getLabel()).isEqualTo("频道ID");
        assertThat(channels.getChildren().get(2).getKey()).isEqualTo("url");
    }
}
