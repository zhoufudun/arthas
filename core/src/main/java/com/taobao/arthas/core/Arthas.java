package com.taobao.arthas.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.taobao.arthas.common.AnsiLog;
import com.taobao.arthas.common.JavaVersionUtils;
import com.taobao.arthas.core.config.Configure;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CLIs;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.Option;
import com.taobao.middleware.cli.TypedOption;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Properties;

/**
 * Arthas启动器
 *
 * Java Agent 是一种能够在不影响正常编译的情况下，修改字节码的技术。java作为一种强类型的语言，
 * 不通过编译就不能能够进行jar包的生成。有了 Java Agent 技术，
 * 就可以在字节码这个层面对类和方法进行修改。也可以把 Java Agent 理解成一种字节码注入的方式。
 *
 * Java Agent支持目标JVM启动时加载，也支持在目标JVM运行时加载，这两种不同的加载模式会使用不同的入口函数:
 * 如果需要在目标JVM启动的同时加载Agent：
 * [1] public static void premain(String agentArgs, Instrumentation inst);
 * [2] public static void premain(String agentArgs);
 * JVM将首先寻找[1]，如果没有发现[1]，再寻找[2]。
 *
 * 如果希望在目标JVM运行时加载Agent，则需要实现下面的方法：
 * [1] public static void agentmain(String agentArgs, Instrumentation inst);
 * [2] public static void agentmain(String agentArgs);
 * 这两组方法的第一个参数AgentArgs是随同 “–javaagent”一起传入的程序参数，如果这个字符串代表了多个参数，
 * 就需要自己解析这些参数。inst是Instrumentation类型的对象，是JVM自动传入的，我们可以拿这个参数进行类增强等操作。
 *
 * 原文链接：https://blog.csdn.net/tianjindong0804/article/details/128423819
 *
 */
public class Arthas {

    private static final String DEFAULT_TELNET_PORT = "3658";
    private static final String DEFAULT_HTTP_PORT = "8563";

    public static void main(String[] args) {
        try {
            new Arthas(args);
        } catch (Throwable t) {
            AnsiLog.error("Start arthas failed, exception stack trace: ");
            t.printStackTrace();
            System.exit(-1);
        }
    }

    private Arthas(String[] args) throws Exception {
        System.out.println("Arthas-core begin, args="+args);
        attachAgent(parse(args));
    }

    private Configure parse(String[] args) {
        Option pid = new TypedOption<Integer>().setType(Integer.class).setShortName("pid").setRequired(true);
        Option core = new TypedOption<String>().setType(String.class).setShortName("core").setRequired(true);
        Option agent = new TypedOption<String>().setType(String.class).setShortName("agent").setRequired(true);
        Option target = new TypedOption<String>().setType(String.class).setShortName("target-ip");
        Option telnetPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("telnet-port").setDefaultValue(DEFAULT_TELNET_PORT);
        Option httpPort = new TypedOption<Integer>().setType(Integer.class)
                .setShortName("http-port").setDefaultValue(DEFAULT_HTTP_PORT);
        Option sessionTimeout = new TypedOption<Integer>().setType(Integer.class)
                        .setShortName("session-timeout").setDefaultValue("" + Configure.DEFAULT_SESSION_TIMEOUT_SECONDS);

        Option tunnelServer = new TypedOption<String>().setType(String.class).setShortName("tunnel-server");
        Option agentId = new TypedOption<String>().setType(String.class).setShortName("agent-id");

        Option statUrl = new TypedOption<String>().setType(String.class).setShortName("stat-url");

        /**
         * 阿里巴巴的命令行解析工具CLI框架，解析参数到CommandLine对象
         */
        CLI cli = CLIs.create("arthas").addOption(pid).addOption(core).addOption(agent).addOption(target)
                .addOption(telnetPort).addOption(httpPort).addOption(sessionTimeout).addOption(tunnelServer).addOption(agentId).addOption(statUrl);
        CommandLine commandLine = cli.parse(Arrays.asList(args));

        /**
         * 传递过来的参数封装成了一个 Configure 对象
         */
        Configure configure = new Configure();
        configure.setJavaPid((Integer) commandLine.getOptionValue("pid"));
        configure.setArthasAgent((String) commandLine.getOptionValue("agent"));
        configure.setArthasCore((String) commandLine.getOptionValue("core"));
        configure.setSessionTimeout((Integer)commandLine.getOptionValue("session-timeout"));
        if (commandLine.getOptionValue("target-ip") == null) {
            throw new IllegalStateException("as.sh is too old to support web console, " +
                    "please run the following command to upgrade to latest version:" +
                    "\ncurl -sLk https://alibaba.github.io/arthas/install.sh | sh");
        }
        configure.setIp((String) commandLine.getOptionValue("target-ip"));
        configure.setTelnetPort((Integer) commandLine.getOptionValue("telnet-port"));
        configure.setHttpPort((Integer) commandLine.getOptionValue("http-port"));

        configure.setTunnelServer((String) commandLine.getOptionValue("tunnel-server"));
        configure.setAgentId((String) commandLine.getOptionValue("agent-id"));
        configure.setStatUrl((String) commandLine.getOptionValue("stat-url"));
        return configure;
    }

    /**
     * 第一步：获取指定PID对应的 VirtualMachineDescriptor，VirtualMachineDescriptor
     *         是连接Java虚拟机的描述对象，有了它就能通过 VirtualMachine#attach 方法连接到指定Java虚拟机进程。
     * 第二步：通过 VirtualMachine#attach 方法连接到指定Java虚拟机进程。
     * 第三步：使用 arthas-agent.jar 增强指定Java进程，这一步实际上就是执行的
     *          com.taobao.arthas.agent334.AgentBootstrap#agentmain方法。
     * @param configure
     * @throws Exception
     */
    private void attachAgent(Configure configure) throws Exception {
        VirtualMachineDescriptor virtualMachineDescriptor = null;
        /**
         * 第一步：获取指定java进程PID对应的 VirtualMachineDescriptor
         */
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String pid = descriptor.id();
            if (pid.equals(Integer.toString(configure.getJavaPid()))) {
                virtualMachineDescriptor = descriptor;
            }
        }
        VirtualMachine virtualMachine = null;
        try {

            // 第二步：连接到Java虚拟机。
            /**
             * 使用 attach(String pid) 这种方式
             */
            if (null == virtualMachineDescriptor) {
                /**
                 * 举例：
                 */
                virtualMachine = VirtualMachine.attach("" + configure.getJavaPid());
            } else {
                /**
                 * 使用 attach(VirtualMachineDescriptor virtualMachineDescriptor) 这种方式
                 */
                virtualMachine = VirtualMachine.attach(virtualMachineDescriptor);
            }

            /**
             *  获取系统变量
             */
            Properties targetSystemProperties = virtualMachine.getSystemProperties();
            /**
             * // 校验一下JDK版本
             */
            String targetJavaVersion = JavaVersionUtils.javaVersionStr(targetSystemProperties);
            String currentJavaVersion = JavaVersionUtils.javaVersionStr();
            if (targetJavaVersion != null && currentJavaVersion != null) {
                if (!targetJavaVersion.equals(currentJavaVersion)) {
                    AnsiLog.warn("Current VM java version: {} do not match target VM java version: {}, attach may fail.",
                                    currentJavaVersion, targetJavaVersion);
                    AnsiLog.warn("Target VM JAVA_HOME is {}, arthas-boot JAVA_HOME is {}, try to set the same JAVA_HOME.",
                                    targetSystemProperties.getProperty("java.home"), System.getProperty("java.home"));
                }
            }
            /**
             * 该参数是通过，arthas-boot.jar 中启动 arthas-agent.jar时 传入的 -core参数，
             * 具体值是 arthas-agent.jar绝对路径
             */
            String arthasAgentPath = configure.getArthasAgent();
            //convert jar path to unicode string
            configure.setArthasAgent(encodeArg(arthasAgentPath));
            configure.setArthasCore(encodeArg(configure.getArthasCore()));
            /**
             * 第三步：加载 arthas-agent.jar，增强目标Java进程，实质上就是运行
             * com.taobao.arthas.agent.AgentBootstrap.agentmain
             *
             * 第二个参数举例：
             * C:\Users\hanshan\.arthas\lib\3.6.7\arthas\\arthas-core.jar;;telnetPort=3658;httpPort=8563;ip=127.0.0.1;arthasAgent=C:\Users\hanshan\.arthas\lib\3.6.7\arthas\\arthas-agent.jar;arthasCore=C:\Users\hanshan\.arthas\lib\3.6.7\arthas\\arthas-core.jar;javaPid=17756;
             */
            virtualMachine.loadAgent(arthasAgentPath,
                    configure.getArthasCore() + ";" + configure.toString());
            System.out.println(arthasAgentPath);
            System.out.println(configure.getArthasCore() + ";" + configure.toString());
        } finally {
            if (null != virtualMachine) {
                virtualMachine.detach();
            }
        }
    }

    private static String encodeArg(String arg) {
        try {
            return URLEncoder.encode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return arg;
        }
    }

}
