package cn.har01d.alist_tvbox;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        Set<Class> classes = findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.dto");
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.dto.bili"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.dto.emby"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.dto.tg"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.tvbox"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.domain"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.model"));
        classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.live.model"));
        //classes.addAll(findAllClassesUsingClassLoader("cn.har01d.alist_tvbox.play.model"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Class clazz : classes) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", clazz.getName());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Field field : clazz.getFields()) {
                Map<String, Object> fieldInfo = new HashMap<>();
                fieldInfo.put("name", field.getName());
                fieldInfo.put("allowWrite", true);
                fields.add(fieldInfo);
            }
            info.put("allDeclaredFields", true);
            info.put("allDeclaredMethods", true);
            info.put("allDeclaredConstructors", true);
            result.add(info);
        }
        addCollections(result);
        result.add(addCustom("com.github.benmanes.caffeine.cache.SSMS"));
        result.add(addCustom("com.github.benmanes.caffeine.cache.SSMSA"));
        result.add(addCustom("com.github.benmanes.caffeine.cache.SSSW"));
        result.add(addCustom("com.github.benmanes.caffeine.cache.PSAMS"));
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(result);
        System.out.println("Working Directory = " + System.getProperty("user.dir"));
        Path path = Paths.get(System.getProperty("user.dir") + "/src/main/resources/META-INF/native-image/reflect-config.json");
        Files.writeString(path, json);
    }

    private static void addCollections(List<Map<String, Object>> result) {
        for (String name : List.of("java.util.HashSet", "java.util.ArrayList", "java.util.HashMap")) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", name);
            List<Map<String, Object>> methods = new ArrayList<>();
            Map<String, Object> method = new HashMap<>();
            method.put("name", "<init>");
            method.put("parameterTypes", List.of());
            methods.add(method);
            info.put("methods", methods);
            result.add(info);
        }
    }

    private static Map<String, Object> addCustom(String name) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", name);
        info.put("allDeclaredFields", true);
        info.put("allDeclaredMethods", true);
        info.put("allDeclaredConstructors", true);
        return info;
    }

    public static Set<Class> findAllClassesUsingClassLoader(String packageName) {
        InputStream stream = ClassLoader.getSystemClassLoader()
                .getResourceAsStream(packageName.replaceAll("[.]", "/"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        return reader.lines()
                .filter(line -> line.endsWith(".class"))
                .map(line -> getClass(line, packageName))
                .collect(Collectors.toSet());
    }

    private static Class getClass(String className, String packageName) {
        try {
            return Class.forName(packageName + "."
                    + className.substring(0, className.lastIndexOf('.')));
        } catch (ClassNotFoundException e) {
            // handle the exception
        }
        return null;
    }
}
