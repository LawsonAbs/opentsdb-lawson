// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.tools;

import java.io.IOException;
import java.lang.reflect.Constructor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

import net.opentsdb.utils.*;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerBossPool;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioWorkerPool;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.tools.BuildData;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.Const;
import net.opentsdb.tsd.PipelineFactory;
import net.opentsdb.tsd.RpcManager;

/**
 * Main class of the TSD, the Time Series Daemon.
 * TSD主类，时间序列守护线程
 */
final class TSDMain {

  /** Prints usage and exits with the given retval. */
  static void usage(final ArgP argp, final String errmsg, final int retval) {
    System.err.println(errmsg);
    System.err.println("Usage: tsd --port=PORT"
      + " --staticroot=PATH --cachedir=PATH\n"
      + "Starts the TSD, the Time Series Daemon");
    if (argp != null) {
      System.err.print(argp.usage());
    }
    System.exit(retval);
  }

  /** A map of configured filters for use in querying */
  private static Map<String, Pair<Class<?>, Constructor<? extends StartupPlugin>>>
          startupPlugin_filter_map = new HashMap<String,
          Pair<Class<?>, Constructor<? extends StartupPlugin>>>();

  private static final short DEFAULT_FLUSH_INTERVAL = 1000;
  
  private static TSDB tsdb = null;
  
  public static void main(String[] args) throws IOException {
    Logger log = LoggerFactory.getLogger(TSDMain.class);//将TSDMain.class这个类作为log标记
    log.info("Starting.");//打印 starting

    //BuildData :Build data for net.opentsdb.tools
    //revisionString() 和 buildString()都只是获取一个string消息的过程
    log.info(BuildData.revisionString());
    log.info(BuildData.buildString());

    try {
      System.in.close();  // Release a FD we don't need.
    } catch (Exception e) {
      log.warn("Failed to close stdin", e);
    }

    //Argp :A dead simple command-line argument parser.
    final ArgP argp = new ArgP();
    CliOptions.addCommon(argp);
    argp.addOption("--port", "NUM", "TCP port to listen on.");
    argp.addOption("--bind", "ADDR", "Address to bind to (default: 0.0.0.0).");
    argp.addOption("--staticroot", "PATH",
                   "Web root from which to serve static files (/s URLs).");
    argp.addOption("--cachedir", "PATH",
                   "Directory under which to cache result of requests.");
    argp.addOption("--worker-threads", "NUM",
                   "Number for async io workers (default: cpu * 2).");
    argp.addOption("--async-io", "true|false",
                   "Use async NIO (default true) or traditional blocking io");
    argp.addOption("--read-only", "true|false",
                   "Set tsd.mode to ro (default false)");
    argp.addOption("--disable-ui", "true|false",
                   "Set tsd.core.enable_ui to false (default true)");
    argp.addOption("--disable-api", "true|false",
                   "Set tsd.core.enable_api to false (default true)");
    argp.addOption("--backlog", "NUM",
                   "Size of connection attempt queue (default: 3072 or kernel"
                   + " somaxconn.");
    argp.addOption("--max-connections", "NUM",
                   "Maximum number of connections to accept");
    argp.addOption("--flush-interval", "MSEC",
                   "Maximum time for which a new data point can be buffered"
                   + " (default: " + DEFAULT_FLUSH_INTERVAL + ").");
    argp.addOption("--statswport", "Force all stats to include the port");
    CliOptions.addAutoMetricFlag(argp);
    args = CliOptions.parse(argp, args);
    args = null; // free().  why args need to be null?

    // get a config object
    //读取配置文件的入口  => 下面这个就是设置properties文件的程序入口
    Config config = CliOptions.getConfig(argp);

      // check for the required parameters
    try {
      if (config.getString("tsd.http.staticroot").isEmpty())
        usage(argp, "Missing static root directory", 1);
    } catch(NullPointerException npe) {
      usage(argp, "Missing static root directory", 1);
    }
    try {
      if (config.getString("tsd.http.cachedir").isEmpty())
        usage(argp, "Missing cache directory", 1);
    } catch(NullPointerException npe) {
      usage(argp, "Missing cache directory", 1);
    }
    try {
      if (!config.hasProperty("tsd.network.port"))
        usage(argp, "Missing network port", 1);
      config.getInt("tsd.network.port");
    } catch (NumberFormatException nfe) {
      usage(argp, "Invalid network port setting", 1);
    }


    // validate the cache and staticroot directories
    try {
      FileSystem.checkDirectory(config.getString("tsd.http.cachedir"),
          Const.MUST_BE_WRITEABLE, Const.CREATE_IF_NEEDED);
      FileSystem.checkDirectory(config.getString("tsd.http.staticroot"),
              !Const.MUST_BE_WRITEABLE, Const.DONT_CREATE);
    } catch (IllegalArgumentException e) {
      usage(argp, e.getMessage(), 3);
    }

    //--------------------------think over----------------------------
    //A ChannelFactory which creates a ServerSocketChannel.
      // 创建一个ServerSocketChannel 的ChannelFactory
    final ServerSocketChannelFactory factory;
    int connections_limit = 0;
    try {
      //默认的tsd.core.connections.limit的值是0  => default_map.put("tsd.core.connections.limit", "0");在Config类中
      connections_limit = config.getInt("tsd.core.connections.limit");
    } catch (NumberFormatException nfe) {
      usage(argp, "Invalid connections limit", 1);
    }

    //Config类中设置的值是：default_map.put("tsd.network.async_io", "true");
    if (config.getBoolean("tsd.network.async_io")) {
      int workers = Runtime.getRuntime().availableProcessors() * 2;

      //Config 类中的default_map.put("tsd.network.worker_threads", "");
      //根据tsd.network.worker_threads这个配置得到线程数
      if (config.hasProperty("tsd.network.worker_threads")) {
        try {
        workers = config.getInt("tsd.network.worker_threads");
        } catch (NumberFormatException nfe) {
          usage(argp, "Invalid worker thread count", 1);
        }
      }


      //都是使用xxpool去提高性能
      final Executor executor = Executors.newCachedThreadPool();
      final NioServerBossPool boss_pool = 
          new NioServerBossPool(executor, 1, new Threads.BossThreadNamer());
      final NioWorkerPool worker_pool = new NioWorkerPool(executor, 
          workers, new Threads.WorkerThreadNamer());
      factory = new NioServerSocketChannelFactory(boss_pool, worker_pool);
    } else {
      factory = new OioServerSocketChannelFactory(
          Executors.newCachedThreadPool(), Executors.newCachedThreadPool(), 
          new Threads.PrependThreadNamer());
    }

    StartupPlugin startup = null;
    try {
      startup = loadStartupPlugins(config);//load startUpPlugins
    } catch (IllegalArgumentException e) {
      usage(argp, e.getMessage(), 3);
    } catch (Exception e) {
      throw new RuntimeException("Initialization failed", e);
    }

    try {
      tsdb = new TSDB(config);
      if (startup != null) {
        tsdb.setStartupPlugin(startup);
      }

      tsdb.initializePlugins(true);
      if (config.getBoolean("tsd.storage.hbase.prefetch_meta")) {
        tsdb.preFetchHBaseMeta();
      }

        System.out.println("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&");
      // Make sure we don't even start if we can't find our tables.
      //！！！！！如果我们不能找到需要的表，则程序不会开始
      tsdb.checkNecessaryTablesExist().joinUninterruptibly();
        System.out.println("###########################################################");

        //please go into the method to look
      registerShutdownHook();

      //ServerBootstrap: A helper class which creates a new server-side Channel and accepts incoming connections.
      // 一个帮助类:去创建一个新的服务器端Channel 并且接受即将到来的连接
      final ServerBootstrap server = new ServerBootstrap(factory);
      
      // This manager is capable of lazy init, but we force an init here to fail fast.
      // RpcManager : Manager for the lifecycle of HttpRpcs, TelnetRpcs, RpcPlugins, and HttpRpcPlugin
      // This process is important, you should get the complete understand!!!!
      final RpcManager manager = RpcManager.instance(tsdb);

      server.setPipelineFactory(new PipelineFactory(tsdb, manager, connections_limit));
      if (config.hasProperty("tsd.network.backlog")) {
        server.setOption("backlog", config.getInt("tsd.network.backlog")); 
      }
      server.setOption("child.tcpNoDelay", 
          config.getBoolean("tsd.network.tcp_no_delay"));
      server.setOption("child.keepAlive", 
          config.getBoolean("tsd.network.keep_alive"));
      server.setOption("reuseAddress", 
          config.getBoolean("tsd.network.reuse_address"));

      // null is interpreted as the wildcard address.  null被解释为通配符地址。
      //This class represents an Internet Protocol (IP) address. 这个类代表一个IP地址
      InetAddress bindAddress = null;

      //default_map.put("tsd.network.bind", "0.0.0.0");
      if (config.hasProperty("tsd.network.bind")) {
        bindAddress = InetAddress.getByName(config.getString("tsd.network.bind"));
      }

      // we validated the network port config earlier
        //tsd.network.port this attribute doesn't have default value,so you must set it in properties
      final InetSocketAddress addr = new InetSocketAddress(bindAddress,
          config.getInt("tsd.network.port"));


      //Creates a new channel which is bound to the specified local address.
        // This operation will block until the channel is bound.
      server.bind(addr);
      if (startup != null) {
        startup.setReady(tsdb);
      }
      log.info("Ready to serve on " + addr);
    } catch (Throwable e) {

        //下面的这个输出在正常情况下不会执行，好像多线程不会执行到这里
        System.out.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");

      factory.releaseExternalResources();
      try {
        if (tsdb != null)
          tsdb.shutdown().joinUninterruptibly();
      } catch (Exception e2) {
        log.error("Failed to shutdown HBase client", e2);
      }
      throw new RuntimeException("Initialization failed", e);
    }

    // The server is now running in separate threads, we can exit main.
    CustomedMethod.printSuffix("main end"); // you can't read this output in stdout
  }


  private static StartupPlugin loadStartupPlugins(Config config) {
    Logger log = LoggerFactory.getLogger(TSDMain.class);

    // load the startup plugin if enabled
    StartupPlugin startup = null;

    if (config.getBoolean("tsd.startup.enable")) {
      log.debug("Startup Plugin is Enabled");
      final String plugin_path = config.getString("tsd.core.plugin_path");
      final String plugin_class = config.getString("tsd.startup.plugin");

      log.debug("Plugin Path: " + plugin_path);
      try {
        TSDB.loadPluginPath(plugin_path);
      } catch (Exception e) {
        log.error("Error loading plugins from plugin path: " + plugin_path, e);
      }

      log.debug("Attempt to Load: " + plugin_class);
      startup = PluginLoader.loadSpecificPlugin(plugin_class, StartupPlugin.class);
      if (startup == null) {
        throw new IllegalArgumentException("Unable to locate startup plugin: " +
                config.getString("tsd.startup.plugin"));
      }
      try {
        startup.initialize(config);
      } catch (Exception e) {
        throw new RuntimeException("Failed to initialize startup plugin", e);
      }
      log.info("Successfully initialized startup plugin [" +
              startup.getClass().getCanonicalName() + "] version: "
              + startup.version());
    } else {
      startup = null;
    }

    return startup;
  }



  //我想知道什么时候回调用这个方法，故在这个方法内部打了断点
  private static void registerShutdownHook() {
    final class TSDBShutdown extends Thread {//TSDBShutdown是一个方法中的内部类，而且该类使用final修饰
      public TSDBShutdown() {
        super("TSDBShutdown");
      }

      //覆写run()方法
      public void run() {
        try {

          //true if the shared instance has been initialized ,false otherwise
          if (RpcManager.isInitialized()) {
            // Check that its actually been initialized.We don't want to create a new instance only to shutdown!
            // 检查RpcManager的实例是否真的被初始化了。我们不想去创建一个新的实例仅仅为了shutdown操作
            RpcManager.instance(tsdb).shutdown().join();
          }

          //再次关闭
          if (tsdb != null) {
            tsdb.shutdown().join();
          }
        } catch (Exception e) {
          LoggerFactory.getLogger(TSDBShutdown.class)
            .error("Uncaught exception during shutdown", e);
        }
      }
    }
    Runtime.getRuntime().addShutdownHook(new TSDBShutdown());
  }
}
