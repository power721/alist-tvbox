package cn.har01d.alist_tvbox.nativeimage;

import cn.har01d.alist_tvbox.service.TgProviderClient;
import cn.har01d.alist_tvbox.web.TelegramController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramNativeDtoBoundaryTest {

    @Test
    void publicTelegramProviderApisShouldNotExposeNestedServiceOrWebDtos() {
        assertThat(Stream.of(TgProviderClient.class, TelegramController.class)
                .flatMap(TelegramNativeDtoBoundaryTest::publicMethodTypes)
                .filter(TelegramNativeDtoBoundaryTest::isNestedServiceOrWebClass)
                .map(Class::getName)
                .distinct())
                .isEmpty();
    }

    private static Stream<Class<?>> publicMethodTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(TelegramNativeDtoBoundaryTest::methodTypes);
    }

    private static Stream<Class<?>> methodTypes(Method method) {
        return Stream.concat(
                        flatten(method.getGenericReturnType()),
                        Arrays.stream(method.getGenericParameterTypes()).flatMap(TelegramNativeDtoBoundaryTest::flatten))
                .filter(Class.class::isInstance)
                .map(Class.class::cast);
    }

    private static Stream<Type> flatten(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return Stream.concat(
                    Stream.of(parameterizedType.getRawType()),
                    Arrays.stream(parameterizedType.getActualTypeArguments()).flatMap(TelegramNativeDtoBoundaryTest::flatten));
        }
        if (type instanceof WildcardType wildcardType) {
            return Stream.concat(
                    Arrays.stream(wildcardType.getUpperBounds()),
                    Arrays.stream(wildcardType.getLowerBounds())).flatMap(TelegramNativeDtoBoundaryTest::flatten);
        }
        if (type instanceof GenericArrayType arrayType) {
            return flatten(arrayType.getGenericComponentType());
        }
        return Stream.of(type);
    }

    private static boolean isNestedServiceOrWebClass(Class<?> type) {
        Package typePackage = type.getPackage();
        if (typePackage == null) {
            return false;
        }
        String packageName = typePackage.getName();
        return type.isMemberClass()
                && (packageName.equals("cn.har01d.alist_tvbox.service")
                || packageName.equals("cn.har01d.alist_tvbox.web"));
    }
}
