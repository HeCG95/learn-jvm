package vertx.fun.proxy;

import javassist.*;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.DecimalFormat;

/**
 * @author HeCG
 * @description xxx
 * @date 2022/12/12 10:37
 * @since 1.0
 */
public class DynamicProxyPerfTest {

    public static void main(String[] args) throws Exception {

        CountService countService = createDubboBytecode(CountService.class);

        CountService delegate = new CountServiceImpl();
        long time = System.currentTimeMillis();
        CountService jdkProxy = createJdkDynamicProxy(delegate);
        time = System.currentTimeMillis() - time;
        System.out.println("Create JDK Proxy: " + time + " ms");

        time = System.currentTimeMillis();
        CountService cglibProxy = createCglibDynamicProxy(delegate);
        time = System.currentTimeMillis() - time;
        System.out.println("Create CGLIB Proxy: " + time + " ms");

        time = System.currentTimeMillis();
        CountService javassistProxy = createJavassistDynamicProxy(delegate);
        time = System.currentTimeMillis() - time;
        System.out.println("Create JAVAASSIST Proxy: " + time + " ms");

        time = System.currentTimeMillis();
        CountService javassistBytecodeProxy = createJavassistBytecodeDynamicProxy(delegate);
        time = System.currentTimeMillis() - time;
        System.out.println("Create JAVAASSIST Bytecode Proxy: " + time + " ms");

        time = System.currentTimeMillis();
        CountService asmBytecodeProxy = createAsmBytecodeDynamicProxy(delegate);
        time = System.currentTimeMillis() - time;
        System.out.println("Create ASM Proxy: " + time + " ms");
        System.out.println("================");

        for (int i = 0; i < 3; i++) {
            test(jdkProxy, "Run JDK Proxy: ");
            test(cglibProxy, "Run CGLIB Proxy: ");
            test(javassistProxy, "Run JAVAASSIST Proxy: ");
            test(javassistBytecodeProxy, "Run JAVAASSIST Bytecode Proxy: ");
            test(asmBytecodeProxy, "Run ASM Bytecode Proxy: ");
            System.out.println("----------------");
        }


    }

    private static CountService createDubboBytecode(Class c){
        com.alibaba.dubbo.common.bytecode.Proxy  proxy = com.alibaba.dubbo.common.bytecode.Proxy.getProxy(c);
        return (CountService)proxy.newInstance();
    }

    private static void test(CountService service, String label)
            throws Exception {
        service.count(); // warm up
        int count = 50000000;
        long time = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            service.count();
        }
        time = System.currentTimeMillis() - time;
        System.out.println(label + time + " ms, " + new DecimalFormat().format(count / time * 1000) + " t/s");
    }

    private static CountService createJdkDynamicProxy(final CountService delegate) {
        CountService jdkProxy = (CountService) Proxy.newProxyInstance(ClassLoader.getSystemClassLoader(),
                new Class[]{CountService.class}, new JdkHandler(delegate));
        return jdkProxy;
    }

    private static class JdkHandler implements InvocationHandler {

        final Object delegate;

        JdkHandler(Object delegate) {
            this.delegate = delegate;
        }

        public Object invoke(Object object, Method method, Object[] objects)
                throws Throwable {
            return method.invoke(delegate, objects);
        }
    }

    private static CountService createCglibDynamicProxy(final CountService delegate) throws Exception {
        Enhancer enhancer = new Enhancer();
        enhancer.setCallback(new CglibInterceptor(delegate));
        enhancer.setInterfaces(new Class[]{CountService.class});
        CountService cglibProxy = (CountService) enhancer.create();
        return cglibProxy;
    }

    private static class CglibInterceptor implements MethodInterceptor {

        final Object delegate;

        CglibInterceptor(Object delegate) {
            this.delegate = delegate;
        }

        public Object intercept(Object object, Method method, Object[] objects,
                                MethodProxy methodProxy) throws Throwable {
            return methodProxy.invoke(delegate, objects);
        }
    }

    private static CountService createJavassistDynamicProxy(final CountService delegate) throws Exception {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(new Class[]{CountService.class});
        Class<?> proxyClass = proxyFactory.createClass();
        CountService javassistProxy = (CountService) proxyClass.newInstance();
        ((ProxyObject) javassistProxy).setHandler(new JavaAssitInterceptor(delegate));
        return javassistProxy;
    }

    private static class JavaAssitInterceptor implements MethodHandler {

        final Object delegate;

        JavaAssitInterceptor(Object delegate) {
            this.delegate = delegate;
        }

        public Object invoke(Object self, Method m, Method proceed,
                             Object[] args) throws Throwable {
            return m.invoke(delegate, args);
        }
    }

    private static CountService createJavassistBytecodeDynamicProxy(CountService delegate) throws Exception {
        ClassPool mPool = new ClassPool(true);
        CtClass mCtc = mPool.makeClass(CountService.class.getName() + "JavaassistProxy");
        mCtc.addInterface(mPool.get(CountService.class.getName()));
        mCtc.addConstructor(CtNewConstructor.defaultConstructor(mCtc));
        mCtc.addField(CtField.make("public " + CountService.class.getName() + " delegate;", mCtc));
        mCtc.addMethod(CtNewMethod.make("public int count() { return delegate.count(); }", mCtc));
        Class<?> pc = mCtc.toClass();
        CountService bytecodeProxy = (CountService) pc.newInstance();
        Field filed = bytecodeProxy.getClass().getField("delegate");
        filed.set(bytecodeProxy, delegate);
        return bytecodeProxy;
    }

    private static CountService createAsmBytecodeDynamicProxy(CountService delegate) throws Exception {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        String className = CountService.class.getName() + "AsmProxy";
        String classPath = className.replace('.', '/');
        String interfacePath = CountService.class.getName().replace('.', '/');
        classWriter.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, classPath, null, "java/lang/Object", new String[]{interfacePath});

        MethodVisitor initVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        initVisitor.visitCode();
        initVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        initVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        initVisitor.visitInsn(Opcodes.RETURN);
        initVisitor.visitMaxs(0, 0);
        initVisitor.visitEnd();

        FieldVisitor fieldVisitor = classWriter.visitField(Opcodes.ACC_PUBLIC, "delegate", "L" + interfacePath + ";", null, null);
        fieldVisitor.visitEnd();

        MethodVisitor methodVisitor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "count", "()I", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, classPath, "delegate", "L" + interfacePath + ";");
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, interfacePath, "count", "()I");
        methodVisitor.visitInsn(Opcodes.IRETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        classWriter.visitEnd();
        byte[] code = classWriter.toByteArray();
        CountService bytecodeProxy = (CountService) new ByteArrayClassLoader().getClass(className, code).newInstance();
        Field filed = bytecodeProxy.getClass().getField("delegate");
        filed.set(bytecodeProxy, delegate);
        return bytecodeProxy;
    }

    private static class ByteArrayClassLoader extends ClassLoader {

        public ByteArrayClassLoader() {
            super(ByteArrayClassLoader.class.getClassLoader());
        }

        public synchronized Class<?> getClass(String name, byte[] code) {
            if (name == null) {
                throw new IllegalArgumentException("");
            }
            return defineClass(name, code, 0, code.length);
        }

    }

}
