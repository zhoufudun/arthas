package com.taobao.arthas.agent;

import java.arthas.Spy;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.util.jar.JarFile;

/**
 * 代理启动类
 *
 * @author vlinux on 15/5/19.
 */
public class AgentBootstrap {

    private static final String ADVICEWEAVER = "com.taobao.arthas.core.advisor.AdviceWeaver";
    private static final String ON_BEFORE = "methodOnBegin";
    private static final String ON_RETURN = "methodOnReturnEnd";
    private static final String ON_THROWS = "methodOnThrowingEnd";
    private static final String BEFORE_INVOKE = "methodOnInvokeBeforeTracing";
    private static final String AFTER_INVOKE = "methodOnInvokeAfterTracing";
    private static final String THROW_INVOKE = "methodOnInvokeThrowTracing";
    private static final String RESET = "resetArthasClassLoader";
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    private static final String ARTHAS_CONFIGURE = "com.taobao.arthas.core.config.Configure";
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String TO_CONFIGURE = "toConfigure";
    private static final String GET_JAVA_PID = "getJavaPid";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";
    private static final String BIND = "bind";

    private static PrintStream ps = System.err;
    static {
        try {
            File arthasLogDir = new File(System.getProperty("user.home") + File.separator + "logs" + File.separator
                    + "arthas" + File.separator);
            if (!arthasLogDir.exists()) {
                arthasLogDir.mkdirs();
            }
            if (!arthasLogDir.exists()) {
                // #572
                arthasLogDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "logs" + File.separator
                        + "arthas" + File.separator);
                if (!arthasLogDir.exists()) {
                    arthasLogDir.mkdirs();
                }
            }

            File log = new File(arthasLogDir, "arthas.log");

            if (!log.exists()) {
                log.createNewFile();
            }
            ps = new PrintStream(new FileOutputStream(log, true));
        } catch (Throwable t) {
            t.printStackTrace(ps);
        }
    }

    // 全局持有classloader用于隔离 Arthas 实现
    private static volatile ClassLoader arthasClassLoader;

    public static void premain(String args, Instrumentation inst) {
        main(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        main(args, inst);
    }

    /**
     * 让下次再次启动时有机会重新加载
     */
    public synchronized static void resetArthasClassLoader() {
        arthasClassLoader = null;
    }

    private static ClassLoader getClassLoader(Instrumentation inst, File spyJarFile, File agentJarFile) throws Throwable {
        // 将Spy添加到BootstrapClassLoader, 优先加载启动类加载器加载arthas-spy.jar
        /**
         * 往类加载器bootstrapClassLoader添加   ClassLoader.loadClass()有效
         * instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(filePath));
         *
         * 往类加载器BootstrapClassPath添加   ClassLoader.getResource()有效。JDK11 不支持这种写法
         * sun.misc.Launcher.getBootstrapClassPath().addURL(new URL("file:"+filePath));
         */
        /**
         * 往类加载器bootstrapClassLoader添加   ClassLoader.loadClass()有效获取这个jar包（arthas-spy.jar）
         * https://juejin.cn/post/6869371903664979976
         */
        inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));
        // 构造自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
        /**
         * 构造自定义的类加载
         */
        return loadOrDefineClassLoader(agentJarFile);
    }

    /**
     *  构造自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
     *
     *  https://juejin.cn/post/6869371903664979976#heading-1
     *
     * @param agentJar
     * @return
     * @throws Throwable
     */
    private static ClassLoader loadOrDefineClassLoader(File agentJar) throws Throwable {
        if (arthasClassLoader == null) {
            /**
             * agentJar加载到自定义的ArthasClassloader加载器，由我们自定义的ArthasClassloader加载agentJar
             */
            arthasClassLoader = new ArthasClassloader(new URL[]{agentJar.toURI().toURL()}); // file:/D:/code/arthas-zfd-3.6.9/packaging/target/arthas-3.1.4-bin/arthas-core.jar
        }
        return arthasClassLoader;
    }

    private static void initSpy(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> adviceWeaverClass = classLoader.loadClass(ADVICEWEAVER);
        Method onBefore = adviceWeaverClass.getMethod(ON_BEFORE, int.class, ClassLoader.class, String.class,
                String.class, String.class, Object.class, Object[].class);
        Method onReturn = adviceWeaverClass.getMethod(ON_RETURN, Object.class);
        Method onThrows = adviceWeaverClass.getMethod(ON_THROWS, Throwable.class);
        Method beforeInvoke = adviceWeaverClass.getMethod(BEFORE_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method afterInvoke = adviceWeaverClass.getMethod(AFTER_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method throwInvoke = adviceWeaverClass.getMethod(THROW_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method reset = AgentBootstrap.class.getMethod(RESET);
        /**
         * 1、Spy类的作用：主要是记录advice的钩子的引用，在增强的时候，会通过反射获取。
         */
        Spy.initForAgentLauncher(classLoader, onBefore, onReturn, onThrows, beforeInvoke, afterInvoke, throwInvoke, reset);
    }

    /**
     * 启动顺序：
     * arthas-boot->arthas-core->arthas-agent
     * @param args
     * @param inst
     */
    private static synchronized void main(String args, final Instrumentation inst) {
        try {
            System.out.println("AgentBootstrap main, args="+args);
            ps.println("Arthas server agent start...");
            // 传递的args参数分两个部分:agentJar路径和agentArgs, 分别是Agent的JAR包路径和期望传递到服务端的参数
            args = decodeArg(args);
            int index = args.indexOf(';');
            String agentJar = args.substring(0, index);
            final String agentArgs = args.substring(index);

            File agentJarFile = new File(agentJar);
            if (!agentJarFile.exists()) {
                ps.println("Agent jar file does not exist: " + agentJarFile);
                return;
            }
            /**
             * arthas-spy.jar可以简单理解为钩子类，基于aop有前置方法，后置方法，这样动态增强类
             */
            File spyJarFile = new File(agentJarFile.getParentFile(), ARTHAS_SPY_JAR);
            if (!spyJarFile.exists()) {
                ps.println("Spy jar file does not exist: " + spyJarFile);
                return;
            }

            /**
             * Use a dedicated thread to run the binding logic to prevent possible memory leak. #195
             * 获取自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
             *
             * https://juejin.cn/post/6869371903664979976
             */
            final ClassLoader agentLoader = getClassLoader(inst, spyJarFile, agentJarFile);

            /**
             * 接下来初始化spy，首先找到通知编织者com.taobao.arthas.core.advisor.AdviceWeaver，然后对各个方法初始化。（这是个伏笔，后面文章command执行这块具体讲到）
             */
            initSpy(agentLoader);

            Thread bindingThread = new Thread() {
                @Override
                public void run() {
                    try {
                        bind(inst, agentLoader, agentArgs);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace(ps);
                    }
                }
            };

            bindingThread.setName("arthas-binding-thread");
            bindingThread.start();
            bindingThread.join();
        } catch (Throwable t) {
            t.printStackTrace(ps);
            try {
                if (ps != System.err) {
                    ps.close();
                }
            } catch (Throwable tt) {
                // ignore
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * 启动 Arthas 服务端
     *
     * bind里面做的事情主要是反射调用ArthasBootstrap类的bind方法，用于启动telnet，暴露端口，用于与commad客户端通讯。
     *
     * @param inst
     * @param agentLoader
     * @param args
     * @throws Throwable
     */
    private static void bind(Instrumentation inst, ClassLoader agentLoader, String args) throws Throwable {
        /**
         * <pre>
         * Configure configure = Configure.toConfigure(args);
         * int javaPid = configure.getJavaPid();
         * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(javaPid, inst);
         * </pre>
         */
        /**
         * 加载com.taobao.arthas.core.config.Configure类
         */
        Class<?> classOfConfigure = agentLoader.loadClass(ARTHAS_CONFIGURE);
        /**
         * 调用：Configure configure = Configure.toConfigure(args);
         */
        Object configure = classOfConfigure.getMethod(TO_CONFIGURE, String.class).invoke(null, args);
        /**
         * 调用：int javaPid = configure.getJavaPid();
         */
        int javaPid = (Integer) classOfConfigure.getMethod(GET_JAVA_PID).invoke(configure);
        /**
         * 加载com.taobao.arthas.core.server.ArthasBootstrap类
         */
        Class<?> bootstrapClass = agentLoader.loadClass(ARTHAS_BOOTSTRAP);
        /**
         * 执行：
         * public synchronized static ArthasBootstrap getInstance(int javaPid, Instrumentation instrumentation) {
         *     if (arthasBootstrap == null) {
         *         arthasBootstrap = new ArthasBootstrap(javaPid, instrumentation);
         *     }
         *     return arthasBootstrap;
         * }
         */
        Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, int.class, Instrumentation.class).invoke(null, javaPid, inst);
        /**
         * 执行：
         *
         *  public boolean isBind() {
         *      return isBindRef.get();
         *  }
         */
        boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
        /**
         * arthas服务器还还没有其他进程绑定，这里开始绑定
         */
        if (!isBind) {
            try {
                ps.println("Arthas start to bind...");
                /**
                 * 调用：
                 * public void bind(Configure configure) throws Throwable {
                 *     。。。省略
                 * }
                 */
                bootstrapClass.getMethod(BIND, classOfConfigure).invoke(bootstrap, configure);
                ps.println("Arthas server bind success.");
                return;
            } catch (Exception e) {
                ps.println("Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.");
                throw e;
            }
        }
        ps.println("Arthas server already bind.");
    }

    private static String decodeArg(String arg) {
        try {
            return URLDecoder.decode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return arg;
        }
    }
}
