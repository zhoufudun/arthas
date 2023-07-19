package com.taobao.arthas.core.server;

import com.taobao.arthas.core.config.Configure;
import com.alibaba.arthas.tunnel.client.TunnelClient;
import com.taobao.arthas.core.command.BuiltinCommandPack;
import com.taobao.arthas.core.shell.ShellServer;
import com.taobao.arthas.core.shell.ShellServerOptions;
import com.taobao.arthas.core.shell.command.CommandResolver;
import com.taobao.arthas.core.shell.handlers.BindHandler;
import com.taobao.arthas.core.shell.impl.ShellServerImpl;
import com.taobao.arthas.core.shell.term.impl.HttpTermServer;
import com.taobao.arthas.core.shell.term.impl.TelnetTermServer;
import com.taobao.arthas.core.util.ArthasBanner;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.UserStatUtil;
import com.taobao.middleware.logger.Logger;

import io.netty.channel.ChannelFuture;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @author vlinux on 15/5/2.
 */
public class ArthasBootstrap {

    private static Logger logger = LogUtil.getArthasLogger();
    private static ArthasBootstrap arthasBootstrap;

    private AtomicBoolean isBindRef = new AtomicBoolean(false);
    private int pid;
    private Instrumentation instrumentation;
    private Thread shutdown;
    private ShellServer shellServer;
    private ExecutorService executorService;
    /**
     * ws协议
     */
    private TunnelClient tunnelClient;

    private ArthasBootstrap(int pid, Instrumentation instrumentation) {
        this.pid = pid;
        this.instrumentation = instrumentation;

        executorService = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "as-command-execute-daemon");
                t.setDaemon(true);
                return t;
            }
        });

        shutdown = new Thread("as-shutdown-hooker") {

            @Override
            public void run() {
                ArthasBootstrap.this.destroy();
            }
        };

        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    /**
     * Bootstrap arthas server
     *
     * @param configure 配置信息
     * @throws IOException 服务器启动失败
     */
    public void bind(Configure configure) throws Throwable {

        long start = System.currentTimeMillis();

        if (!isBindRef.compareAndSet(false, true)) {
            throw new IllegalStateException("already bind");
        }

        String agentId = null;
        try {
            if (configure.getTunnelServer() != null && configure.getHttpPort() > 0) {
                tunnelClient = new TunnelClient();
                tunnelClient.setId(configure.getAgentId());
                tunnelClient.setTunnelServerUrl(configure.getTunnelServer());
                // ws://127.0.0.1:8563/ws
                String host = "127.0.0.1";
                if(configure.getIp() != null) {
                    host = configure.getIp();
                }
                // ws://127.0.0.1:8563/ws
                URI uri = new URI("ws", null, host, configure.getHttpPort(), "/ws", null, null);
                tunnelClient.setLocalServerUrl(uri.toString());
                /**
                 * 连接ws服务端
                 */
                ChannelFuture channelFuture = tunnelClient.start();
                channelFuture.await(10, TimeUnit.SECONDS);
                if(channelFuture.isSuccess()) {
                    agentId = tunnelClient.getId();
                }
            }
        } catch (Throwable t) {
            logger.error("arthas", "start tunnel client error", t);
        }

//        启动命令行服务器
        try {
            ShellServerOptions options = new ShellServerOptions()
                            .setInstrumentation(instrumentation)
                            .setPid(pid)
                            .setSessionTimeout(configure.getSessionTimeout() * 1000);

            if (agentId != null) {
                Map<String, String> welcomeInfos = new HashMap<String, String>();
                welcomeInfos.put("id", agentId);
                options.setWelcomeMessage(ArthasBanner.welcome(welcomeInfos));
            }
            shellServer = new ShellServerImpl(options, this);

            BuiltinCommandPack builtinCommands = new BuiltinCommandPack();

            List<CommandResolver> resolvers = new ArrayList<CommandResolver>();
            resolvers.add(builtinCommands);
            // TODO: discover user provided command resolver

            /**
             * 接下来这一步注册通讯方式，现在支持两种，一种是telnet，一种是websocket，这里默认两种都初始化了，
             * 当然这里还可以扩展其他方式，这里做的好点其实可以基于SPI去模块扩展。
             */
            if (configure.getTelnetPort() > 0) {
                /**
                 * telnet通讯方式注册（内存保存）
                 */
                shellServer.registerTermServer(new TelnetTermServer(configure.getIp(), configure.getTelnetPort(),
                                options.getConnectionTimeout()));
            } else {
                logger.info("telnet port is {}, skip bind telnet server.", configure.getTelnetPort());
            }
            if (configure.getHttpPort() > 0) {
                /**
                 * websocket通讯方式注册（内存保存）
                 */
                shellServer.registerTermServer(new HttpTermServer(configure.getIp(), configure.getHttpPort(),
                                options.getConnectionTimeout()));
            } else {
                logger.info("http port is {}, skip bind http server.", configure.getHttpPort());
            }

            for (CommandResolver resolver : resolvers) {
                /**
                 * 支持的指令保存在shell服务端（内存保存）
                 */
                shellServer.registerCommandResolver(resolver);
            }
            /**
             * 接下来启动服务器监听了，这里面用到了一个第三方框架termd（是一款优秀的命令行程序开发框架）
             */
            shellServer.listen(new BindHandler(isBindRef)); // BindHandler客户端第一次连接才使用，之后不使用

            logger.info("as-server listening on network={};telnet={};http={};timeout={};", configure.getIp(),
                    configure.getTelnetPort(), configure.getHttpPort(), options.getConnectionTimeout());
            // 异步回报启动次数
            if (configure.getStatUrl() != null) {
                logger.info("arthas stat url: {}", configure.getStatUrl());
            }
            UserStatUtil.setStatUrl(configure.getStatUrl());
//            / 异步回报启动次数
            UserStatUtil.arthasStart();

            logger.info("as-server started in {} ms", System.currentTimeMillis() - start );
        } catch (Throwable e) {
            logger.error(null, "Error during bind to port " + configure.getTelnetPort(), e);
            if (shellServer != null) {
                shellServer.close();
            }
            throw e;
        }
    }

    /**
     * 判断服务端是否已经启动
     *
     * @return true:服务端已经启动;false:服务端关闭
     */
    public boolean isBind() {
        return isBindRef.get();
    }

    public void destroy() {
        if (this.tunnelClient != null) {
            try {
                tunnelClient.stop();
            } catch (Throwable e) {
                logger.error("arthas", "stop tunnel client error", e);
            }
        }
        executorService.shutdownNow();
        UserStatUtil.destroy();
        // clear the reference in Spy class.
        cleanUpSpyReference();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (Throwable t) {
            // ignore
        }
        logger.info("as-server destroy completed.");
        // see https://github.com/alibaba/arthas/issues/319
        LogUtil.closeResultLogger();
    }

    /**
     * 单例
     *
     * @param instrumentation JVM增强
     * @return ArthasServer单例
     */
    public synchronized static ArthasBootstrap getInstance(int javaPid, Instrumentation instrumentation) {
        if (arthasBootstrap == null) {
            arthasBootstrap = new ArthasBootstrap(javaPid, instrumentation);
        }
        return arthasBootstrap;
    }
    /**
     * @return ArthasServer单例
     */
    public static ArthasBootstrap getInstance() {
        if (arthasBootstrap == null) {
            throw new IllegalStateException("ArthasBootstrap must be initialized before!");
        }
        return arthasBootstrap;
    }

    public void execute(Runnable command) {
        executorService.execute(command);
    }

    /**
     * 清除spy中对classloader的引用，避免内存泄露
     */
    private void cleanUpSpyReference() {
        try {
            // 从ArthasClassLoader中加载Spy
            Class<?> spyClass = this.getClass().getClassLoader().loadClass(Constants.SPY_CLASSNAME);
            Method agentDestroyMethod = spyClass.getMethod("destroy");
            agentDestroyMethod.invoke(null);
        } catch (ClassNotFoundException e) {
            logger.error(null, "Spy load failed from ArthasClassLoader, which should not happen", e);
        } catch (Exception e) {
            logger.error(null, "Spy destroy failed: ", e);
        }
    }

    public TunnelClient getTunnelClient() {
        return tunnelClient;
    }
}
