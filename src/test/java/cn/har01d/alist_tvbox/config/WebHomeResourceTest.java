package cn.har01d.alist_tvbox.config;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /webhome/** 覆盖语义(文件页面-静态文件上传自定义首页):
 * static/webhome/ 下同名文件优先于内置 classpath app.html,删除覆盖文件后回落内置。
 * homePage URL 固定,响应必须 no-cache 才能让覆盖更新即时可见。
 */
class WebHomeResourceTest {
    /** 内置 app.html 中的稳定 ASCII 标记(页面注入 ATV_TOKEN/ATV_SERVER 配置)。 */
    private static final String BUILTIN_MARKER = "ATV_TOKEN";
    private static final String OVERRIDE_MARKER = "custom-webhome-override-42";

    /** 内置页 classpath:/static/webhome/app.html 是 web-ui 构建产物(真源 web-ui/public,static/ 整目录 gitignore):
     * 没跑过 npm build 的环境(新 clone 裸 mvn test、无前端步骤的 build-base CI)该资源不存在,
     * 内置页相关断言跳过而非假红;overrideBundleAssetsAreServed 只依赖覆盖目录,不受影响。 */
    private static boolean builtinPageAvailable() {
        return WebHomeResourceTest.class.getResource("/static/webhome/app.html") != null;
    }

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private Path overrideDir;

    /** getHandlerMapping 是 protected,子类提升可见性供测试直接取映射。 */
    static class ExposedRegistry extends ResourceHandlerRegistry {
        ExposedRegistry(ApplicationContext context, ServletContext servletContext) {
            super(context, servletContext);
        }

        @Override
        public AbstractHandlerMapping getHandlerMapping() {
            return super.getHandlerMapping();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        overrideDir = Files.createDirectories(tempDir.resolve("static").resolve("webhome"));

        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.refresh();

        // 覆盖目录注入临时目录(生产为 /www/static/webhome),FileSystemResource 路径注册时固化
        ExposedRegistry registry = new ExposedRegistry(context, context.getServletContext());
        MvcConfig.registerWebHomeOverride(registry, overrideDir);
        AbstractHandlerMapping mapping = registry.getHandlerMapping();

        // 手动补上真实容器会给嵌套 handler 的生命周期回调(registerSingleton 不触发)
        for (Object handler : ((AbstractUrlHandlerMapping) mapping).getHandlerMap().values()) {
            if (handler instanceof ResourceHttpRequestHandler resourceHandler) {
                resourceHandler.setApplicationContext(context);
                resourceHandler.setServletContext(context.getServletContext());
                resourceHandler.afterPropertiesSet();
            }
        }
        mapping.setApplicationContext(context);
        context.getBeanFactory().registerSingleton("resourceHandlerMapping", mapping);

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void noOverrideFallsBackToBuiltinPage() throws Exception {
        Assumptions.assumeTrue(builtinPageAvailable(), "缺少 web-ui 构建产物 static/webhome/app.html(先 cd web-ui && npm run build)");
        mockMvc.perform(get("/webhome/app.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BUILTIN_MARKER)))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void uploadedAppHtmlOverridesBuiltinAndRestoreOnDelete() throws Exception {
        Assumptions.assumeTrue(builtinPageAvailable(), "缺少 web-ui 构建产物 static/webhome/app.html(先 cd web-ui && npm run build)");
        Files.writeString(overrideDir.resolve("app.html"),
                "<html><body>" + OVERRIDE_MARKER + "</body></html>");
        mockMvc.perform(get("/webhome/app.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(OVERRIDE_MARKER)))
                .andExpect(content().string(not(containsString(BUILTIN_MARKER))));

        Files.delete(overrideDir.resolve("app.html"));
        mockMvc.perform(get("/webhome/app.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(BUILTIN_MARKER)));
    }

    /** zip 解压上传的自定义页可带 css/js 等相对资源,须与 app.html 同级可访问。 */
    @Test
    void overrideBundleAssetsAreServed() throws Exception {
        Files.writeString(overrideDir.resolve("style.css"), "body{background:#000}");
        mockMvc.perform(get("/webhome/style.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("background")));
    }
}
