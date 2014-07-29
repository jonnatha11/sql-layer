package com.foundationdb.sql.parser;

import org.hamcrest.core.StringEndsWith;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.reflections.Reflections;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by scott on 7/29/14.
 */
public class CheckParserUsagesIT {

    private static Set<Class<? extends QueryTreeNode>> queryTreeNodes;
    private static Collection<String> sqlLayerClassPaths;
    private PropertyFinder finder;

    @BeforeClass
    public static void getParserClasses() {
        Reflections reflections = new Reflections("com.foundationdb.sql.parser");
        queryTreeNodes = reflections.getSubTypesOf(QueryTreeNode.class);
    }

    @BeforeClass
    public static void getSqlLayerClassNames() throws Exception {
        sqlLayerClassPaths = getClassesInPackage("com.foundationdb.sql", "com.foundationdb.sql.Main");
        System.out.println(sqlLayerClassPaths);
    }

    private static Collection<String> getClassesInPackage(String packageName, String sampleClass) {
        String sampleClassPathSuffix = sampleClass.replaceAll("\\.", "/") + ".class";
        String sampleClassPath = CheckParserUsagesIT.class.getClassLoader().getResource(sampleClassPathSuffix).getPath();
        assertThat(sampleClassPath, new StringEndsWith(sampleClassPathSuffix));
        String packagePath = sampleClassPath.substring(0,sampleClassPath.length()-sampleClassPathSuffix.length()) +
                packageName.replaceAll("\\.", "/");
        return getAllClassesInDirectory(new File(packagePath));
    }

    private static Collection<String> getAllClassesInDirectory(File directory) {
        Collection<String> result = new HashSet<>();
        for (File file : directory.listFiles()) {
            if (file.isDirectory())
            {
                result.addAll(getAllClassesInDirectory(file));
            } else if (file.isFile() && file.getName().endsWith(".class")) {
                result.add(file.getAbsolutePath());
            }
        }
        return result;
    }

    @Before
    public void initializeFinder() {
        finder = new PropertyFinder();
        for (Class<? extends QueryTreeNode> nodeClass : queryTreeNodes) {
            try {
                ClassReader reader = new ClassReader(nodeClass.getName());
                reader.accept(finder, 0);
            } catch (IOException e) {
                System.err.println("Could not open class to scan: " + nodeClass.getName());
                System.exit(1);
            }
        }
    }

    @Test
    public void testAllReferencedClassesHaveReferencedGetters() {
        System.out.println(finder);
    }

    public class PropertyFinder extends ClassVisitor {

        private Map<String, NodeClass> nodes;
        private NodeClass currentClass;

        public PropertyFinder() {
            super(Opcodes.ASM5);
            nodes = new HashMap<String, NodeClass>();
        }

        public Map<String, NodeClass> getNodes() {
            return nodes;
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName, String[] interfaces) {
            currentClass = new NodeClass(name, superName);
            nodes.put(name, currentClass);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String signature, Object value) {
            currentClass.addField(access, name);
            return null;
        }

        // TODO add interface & base class methods
        @Override
        public MethodVisitor visitMethod(int access, String name,
                                         String desc, String signature, String[] exceptions) {
            currentClass.addMethod(access, name, desc);
            return null;
        }

        @Override
        public String toString() {
            StringBuilder stringBuilder = new StringBuilder();
            for (NodeClass node : nodes.values()) {
                stringBuilder.append(node.toString());
                stringBuilder.append("\n");
            }
            return stringBuilder.toString();
        }

    }

    public static class NodeClass {
        public String name;
        public String baseClassName;
        public NodeClass baseClass;
        public Set<String> fields;
        private Set<Method> methods;

        public NodeClass(String name, String baseClassName) {
            this.name = name;
            this.baseClassName = baseClassName;
            fields = new HashSet<String>();
            methods = new HashSet<Method>();
        }

        public String getName() {
            return name;
        }

        public String getBaseClassName() {
            return baseClassName;
        }

        public NodeClass getBaseClass() {
            return baseClass;
        }

        public void setBaseClass(NodeClass baseClass) {
            this.baseClass = baseClass;
        }

        public void addField(int access, String fieldName) {
            if ((access & Opcodes.ACC_PUBLIC) > 0) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    fields.add(fieldName);
                    System.out.println("WARNING " + this.name.replaceAll("/",".") + " has a public field: " + fieldName);
                }
            }
        }

        public Method addMethod(int access, String name, String descriptor) {
            if ((access & Opcodes.ACC_PUBLIC) > 0) {
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    if (name.startsWith("get")) {
                        Method method = new Method(name, descriptor);
                        methods.add(method);
                        return method;
                    }
                }
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(name);
            stringBuilder.append(": ");
            for (String field : fields) {
                stringBuilder.append(field);
                stringBuilder.append(", ");
            }
            for (Method method: methods) {
                stringBuilder.append(method);
                stringBuilder.append(", ");
            }
            return stringBuilder.toString();
        }

        public void usedMethod(String name, String desc) {
            if (name.startsWith("get")) {
                System.out.println("Method usage" + this.name + "." + name + " "  +desc);
                // TODO switch to map<string, Set<Method>>
                for (Method method : methods) {
                    if (method.matches(name, desc)) {
                        methods.remove(method);
                        break;
                    }
                }
            }
        }

        public boolean fullyUsed() {
            return methods.size() == 0 && fields.size() == 0;
        }

        public static class Method {

            private String name;

            public Method(String name, String descriptor) {
                this.name = name;
                parseDescriptor(descriptor);
            }

            private String parseDescriptor(String descriptor) {
                // check that it only has a return type
                if (!Pattern.matches("^\\(\\).+", descriptor)) {
                    System.out.println("WARNING: method has parameters, or no return type: " + name + " " + descriptor);
                }
                return null;
            }

            @Override
            public String toString() {
                return name;
            }

            public boolean matches(String name, String descriptor) {
                if (!this.name.equals(name)) {
                    return false;
                }
                // check that it only has a return type
                if (!Pattern.matches("^\\(\\).+", descriptor)) {
                    System.out.println("WARNING: method has parameters, or no return type: " + name + " " + descriptor);
                    return false;
                }
                return true;
            }
        }
    }


}


