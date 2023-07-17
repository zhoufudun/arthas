package com.taobao.arthas.common;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import javax.net.ServerSocketFactory;


/**
 *
 * @author hengyunabc 2018-11-07
 *
 */
public class SocketUtils {
    /**
     * 根据不同的平台判断指定端口是否占用，如果占用，返回占用该端口的pid
     * @param port
     * @return
     */
    public static int findTcpListenProcess(int port) {
        try {
            if (OSUtils.isWindows()) {
                String[] command = { "netstat", "-ano", "-p", "TCP" };
                List<String> lines = ExecutingCommand.runNative(command);
                for (String line : lines) {
                    if (line.contains("LISTENING")) {
                        // TCP 0.0.0.0:49168 0.0.0.0:0 LISTENING 476
                        String[] strings = line.trim().split("\\s+");
                        if (strings.length == 5) {
                            if (strings[1].endsWith(":" + port)) {
                                return Integer.parseInt(strings[4]); // line举例子：  TCP    127.0.0.1:3658   0.0.0.0:0   LISTENING   4960
                            }
                        }
                    }
                }
            }

            if (OSUtils.isLinux() || OSUtils.isMac()) {
                String pid = ExecutingCommand.getFirstAnswer("lsof -t -s TCP:LISTEN -i TCP:" + port);
                if (!pid.trim().isEmpty()) {
                    return Integer.parseInt(pid);
                }
            }
        } catch (Throwable e) {
            // ignore
        }

        return -1;
    }

    public static boolean isTcpPortAvailable(int port) {
        try {
            ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(port, 1,
                            InetAddress.getByName("localhost"));
            serverSocket.close();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

}
