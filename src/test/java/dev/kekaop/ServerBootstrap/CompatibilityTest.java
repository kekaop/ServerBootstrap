package dev.kekaop.ServerBootstrap;

import org.bukkit.command.*;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityTest {
    @Test void pluginUsesJava17Bytecode() throws Exception {
        try (var in=new DataInputStream(ServerBootstrap.class.getResourceAsStream("ServerBootstrap.class"))) {
            assertEquals(0xcafebabe,in.readInt()); in.readUnsignedShort(); assertEquals(61,in.readUnsignedShort());
        }
    }
    @Test void pluginDescriptorHasMinimumApiAndStartupLoadOrder() throws Exception {
        try (var input=ServerBootstrap.class.getResourceAsStream("/plugin.yml")) {
            String descriptor=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(descriptor.contains("api-version: '1.20'")); assertTrue(descriptor.contains("load: STARTUP"));
            assertTrue(descriptor.contains("default: false")); assertFalse(descriptor.contains("${version}"));
            // Permission is enforced by the executor so default:false cannot accidentally block console dispatch.
            assertFalse(descriptor.contains("permission: serverbootstrap.admin"));
        }
    }
    @Test void administrativePermissionIsExplicitAndConsoleAlwaysWorks() {
        assertFalse(SetupCommand.allowed(sender(CommandSender.class,false)));
        assertTrue(SetupCommand.allowed(sender(CommandSender.class,true)));
        assertTrue(SetupCommand.allowed(sender(ConsoleCommandSender.class,false)));
    }
    private CommandSender sender(Class<?> type,boolean permitted) {
        return (CommandSender)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if (method.getName().equals("hasPermission")) return permitted;
            if (method.getReturnType().equals(boolean.class)) return false;
            return null;
        });
    }
}
