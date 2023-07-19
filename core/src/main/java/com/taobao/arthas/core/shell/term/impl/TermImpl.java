package com.taobao.arthas.core.shell.term.impl;

import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.handlers.term.CloseHandlerWrapper;
import com.taobao.arthas.core.shell.handlers.term.DefaultTermStdinHandler;
import com.taobao.arthas.core.shell.handlers.term.EventHandler;
import com.taobao.arthas.core.shell.handlers.Handler;
import com.taobao.arthas.core.shell.handlers.term.RequestHandler;
import com.taobao.arthas.core.shell.handlers.term.SizeHandlerWrapper;
import com.taobao.arthas.core.shell.handlers.term.StdinHandlerWrapper;
import com.taobao.arthas.core.shell.session.Session;
import com.taobao.arthas.core.shell.term.SignalHandler;
import com.taobao.arthas.core.shell.term.Term;
import com.taobao.arthas.core.util.Constants;
import com.taobao.arthas.core.util.FileUtils;
import io.termd.core.function.Consumer;
import io.termd.core.readline.Function;
import io.termd.core.readline.Keymap;
import io.termd.core.readline.Readline;
import io.termd.core.tty.TtyConnection;
import io.termd.core.util.Helper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class TermImpl implements Term {

    private static final List<Function> readlineFunctions = Helper.loadServices(Function.class.getClassLoader(), Function.class);

    private Readline readline;
    private Consumer<int[]> echoHandler;
    private TtyConnection conn;
    private volatile Handler<String> stdinHandler;
    private List<io.termd.core.function.Function<String, String>> stdoutHandlerChain;
    private SignalHandler interruptHandler;
    private SignalHandler suspendHandler;
    private Session session;
    private boolean inReadline;

    public TermImpl(TtyConnection conn) {
        this(com.taobao.arthas.core.shell.term.impl.Helper.loadKeymap(), conn);
    }

    /**
     * TermImpl内部首先可以看到对Function类通过spi进行了所有Function的加载，
     * Function就是刚才快捷键对应的处理类，下面随便看看一个类，快捷键向上看历史命令。
     *
     *
     * readlineFunctions通过spi获取所有的实现，举个例子看：HistorySearchBackward
     *
     * public class HistorySearchBackward implements Function {
     *   @Override
     *   public String name() {
     *       return "history-search-backward";
     *   }
     *   @Override
     *   public void apply(Readline.Interaction interaction) {
     *       LineBuffer buf = interaction.buffer().copy();
     *       int cursor = buf.getCursor();
     *       List<int[]> history = interaction.history();
     *
     *       int curr = interaction.getHistoryIndex();
     *
     *       int searchStart = curr + 1;
     *
     *       for (int i = searchStart; i < history.size(); ++i) {
     *           int[] line = history.get(i);
     *           if (LineBufferUtils.equals(buf, line)) {
     *               continue;
     *           }
     *           if (LineBufferUtils.matchBeforeCursor(buf, line)) {
     *               interaction.refresh(new LineBuffer().insert(line).setCursor(cursor));
     *               interaction.setHistoryIndex(i);
     *               break;
     *           }
     *       }
     *       interaction.resume();
     *   }
     * }
     *
     *
     *
     * 上面可以看到readline的字段history，通过本地history文件加载出来。我们执行的历史命令都会存储到history文件中。 可以猜测history命令怎么查找所有历史命令，就是这样拿出来的。
     * 接着实例化DefaultTermStdinHandler，EventHandler以及对应的赋值，然后结合term框架，对相应的快捷键进行处理，这里就不多说，感兴趣自行去看。下面会重点说明help的整个过程。
     * 回到上面termHandler.handle(new TermImpl(Helper.loadKeymap(), conn));这一行代码。回顾一下最上面termServer listen的时候, termServer.termHandler(new TermServerTermHandler(this));
     * 实例化了TermServerTermHandler。所以这里执行了TermServerTermHandler.handle方法
     *
     * @param keymap
     * @param conn
     */
    public TermImpl(Keymap keymap, TtyConnection conn) {
        this.conn = conn;
        readline = new Readline(keymap);
        readline.setHistory(FileUtils.loadCommandHistory(new File(Constants.CMD_HISTORY_FILE)));
        for (Function function : readlineFunctions) {
            readline.addFunction(function);
        }

        echoHandler = new DefaultTermStdinHandler(this);
        conn.setStdinHandler(echoHandler);
        conn.setEventHandler(new EventHandler(this));
    }

    @Override
    public Term setSession(Session session) {
        this.session = session;
        return this;
    }

    @Override
    public void readline(String prompt, Handler<String> lineHandler) {
        if (conn.getStdinHandler() != echoHandler) {
            throw new IllegalStateException();
        }
        if (inReadline) {
            throw new IllegalStateException();
        }
        inReadline = true;
        readline.readline(conn, prompt, new RequestHandler(this, lineHandler));
    }
    // linHandler:ShellLineHandler     completionHandler:CommandManagerCompletionHandler
    public void readline(String prompt, Handler<String> lineHandler, Handler<Completion> completionHandler) {
        if (conn.getStdinHandler() != echoHandler) { //echoHandler:DefaultTermStdinHandler  conn: TelnetTtyConnection
            throw new IllegalStateException();
        }
        if (inReadline) {
            throw new IllegalStateException();
        }
        inReadline = true;
        readline.readline(conn, prompt, new RequestHandler(this, lineHandler), new CompletionHandler(completionHandler, session));
    }

    @Override
    public Term closeHandler(final Handler<Void> handler) {
        if (handler != null) {
            conn.setCloseHandler(new CloseHandlerWrapper(handler));
        } else {
            conn.setCloseHandler(null);
        }
        return this;
    }

    public long lastAccessedTime() {
        return conn.lastAccessedTime();
    }

    @Override
    public String type() {
        return conn.terminalType();
    }

    @Override
    public int width() {
        return conn.size() != null ? conn.size().x() : -1;
    }

    @Override
    public int height() {
        return conn.size() != null ? conn.size().y() : -1;
    }

    void checkPending() {
        if (stdinHandler != null && readline.hasEvent()) {
            stdinHandler.handle(Helper.fromCodePoints(readline.nextEvent().buffer().array()));
            checkPending();
        }
    }

    @Override
    public TermImpl resizehandler(Handler<Void> handler) {
        if (inReadline) {
            throw new IllegalStateException();
        }
        if (handler != null) {
            conn.setSizeHandler(new SizeHandlerWrapper(handler));
        } else {
            conn.setSizeHandler(null);
        }
        return this;
    }

    @Override
    public Term stdinHandler(final Handler<String> handler) {
        if (inReadline) {
            throw new IllegalStateException();
        }
        stdinHandler = handler;
        if (handler != null) {
            conn.setStdinHandler(new StdinHandlerWrapper(handler));
            checkPending();
        } else {
            conn.setStdinHandler(echoHandler);
        }
        return this;
    }

    @Override
    public Term stdoutHandler(io.termd.core.function.Function<String, String>  handler) {
        if (stdoutHandlerChain == null) {
            stdoutHandlerChain = new ArrayList<io.termd.core.function.Function<String, String>>();
        }
        stdoutHandlerChain.add(handler);
        return this;
    }

    @Override
    public Term write(String data) {
        if (stdoutHandlerChain != null) {
            for (io.termd.core.function.Function<String, String> function : stdoutHandlerChain) {
                data = function.apply(data);
            }
        }
        conn.write(data);
        /**
         * watch举例：
         * ts=2023-07-19 15:50:39; [cost=1331861.2534ms] result=@ArrayList[
         *     @Object[][
         *         @Integer[124220],
         *         @ArrayList[
         *             @Integer[2],
         *             @Integer[2],
         *             @Integer[5],
         *             @Integer[6211],
         *         ],
         *     ],
         *     null,
         *     null,
         * ]
         */
        return this;
    }

    public TermImpl interruptHandler(SignalHandler handler) {
        interruptHandler = handler;
        return this;
    }

    public TermImpl suspendHandler(SignalHandler handler) {
        suspendHandler = handler;
        return this;
    }

    public void close() {
        conn.close();
        FileUtils.saveCommandHistory(readline.getHistory(), new File(Constants.CMD_HISTORY_FILE));
    }

    public TermImpl echo(String text) {
        echo(Helper.toCodePoints(text));
        return this;
    }

    public void setInReadline(boolean inReadline) {
        this.inReadline = inReadline;
    }

    public Readline getReadline() {
        return readline;
    }

    public void handleIntr(Integer key) {
        if (interruptHandler == null || !interruptHandler.deliver(key)) {
            echo(key, '\n');
        }
    }

    public void handleEof(Integer key) {
        // Pseudo signal
        if (stdinHandler != null) {
            stdinHandler.handle(Helper.fromCodePoints(new int[]{key}));
        } else {
            echo(key);
            readline.queueEvent(new int[]{key});
        }
    }

    public void handleSusp(Integer key) {
        if (suspendHandler == null || !suspendHandler.deliver(key)) {
            echo(key, 'Z' - 64);
        }
    }

    public void echo(int... codePoints) {
        Consumer<int[]> out = conn.stdoutHandler();
        for (int codePoint : codePoints) {
            if (codePoint < 32) {
                if (codePoint == '\t') {
                    out.accept(new int[]{'\t'});
                } else if (codePoint == '\b') {
                    out.accept(new int[]{'\b', ' ', '\b'});
                } else if (codePoint == '\r' || codePoint == '\n') {
                    out.accept(new int[]{'\n'});
                } else {
                    out.accept(new int[]{'^', codePoint + 64});
                }
            } else {
                if (codePoint == 127) {
                    out.accept(new int[]{'\b', ' ', '\b'});
                } else {
                    out.accept(new int[]{codePoint});
                }
            }
        }
    }
}
