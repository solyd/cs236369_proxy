package il.technion.cs236369.proxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.HttpVersion;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Named;

// chromium --proxy-server="localhost:8080"

public class HttpProxy {

    public static final int MAX_RESPONSE_SIZE = 65535; // bytes
    public static final String CLIENT_CONN = "CLIENT_CONN";
    public static final String TARGET_CONN = "TARGET_CONN";

    private static final BasicHttpResponse ERR_RESPONSE =
        new BasicHttpResponse(HttpVersion.HTTP_1_1, 500, "Internal Server Error");

    // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // Logger stuff
    //
    private static Logger log = Logger.getLogger(HttpProxy.class.getName());

    static {
        log.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new ProxyLogFormatter());
        log.addHandler(handler);
        //log.setLevel(Level.SEVERE);
    }

    static class ProxyLogFormatter extends Formatter {
        @Override
        public String format(LogRecord rec) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            //sb.append("[").append(rec.getSourceClassName()).append(".");
            //sb.append(rec.getSourceMethodName()).append(" - ");
            sb.append(rec.getLevel()).append("] ");
            sb.append(formatMessage(rec));
            sb.append("\n");
            return sb.toString();
        }

    }
    // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    private ServerSocketFactory m_servSockFact;
    private SocketFactory       m_clientSockFact;
    private int                 m_port;
    private ServerSocket        m_socket;
    private HttpParams          m_httpparams;
    private HttpService         m_httpservice;
    private ProxyCache          m_cache;

    /**
     * Constructs the proxy
     *
     * @param sockFactory The SocketFactory to be used for creating new sockets
     *        for connecting to servers
     *
     * @param srvSockFactory The ServerSocketFactory to be used for creating a
     *        single ServerSocket for listening for clients requests
     *
     * @param port The port number to bounded by the ServerSocket
     *
     * @param dbURL url of the database (e.g. jdbc:mysql://127.0.0.1:3306/)
     * @param dbName The name of the database (e.g. proxy)
     * @param tblName The name of the table in the database (e.g. cache)
     * @param dbUsername Database's username (e.g. root)
     * @param dbPassword Database's password
     * @param dbDriver Database's driver class name (com.mysql.jdbc.Driver)
     */

    @SuppressWarnings("deprecation")
    @Inject
    HttpProxy(SocketFactory sockFactory, ServerSocketFactory srvSockFactory,
              @Named("httproxy.net.port") int port,
              @Named("httproxy.db.url") String dbURL,
              @Named("httproxy.db.name") String dbName,
              @Named("httproxy.db.table") String tblName,
              @Named("httproxy.db.username") String dbUsername,
              @Named("httproxy.db.password") String dbPassword,
              @Named("httproxy.db.driver") String dbDriver) {
        log.info("Initializing Http Proxy");

        m_clientSockFact = sockFactory;
        m_servSockFact = srvSockFactory;
        m_port = port;


        // Setup HTTP request handling
        m_httpparams = new SyncBasicHttpParams();
        m_httpparams.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000);
        m_httpparams.setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024);
        m_httpparams.setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false);
        m_httpparams.setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);
        m_httpparams.setParameter(CoreProtocolPNames.ORIGIN_SERVER, "HttpComponents/1.1");

        // Set up HTTP protocol processor for incoming connections
        BasicHttpProcessor inhttpproc = new BasicHttpProcessor();
        inhttpproc.addInterceptor(new ResponseDate());
        inhttpproc.addInterceptor(new ResponseServer());
        inhttpproc.addInterceptor(new ResponseContent());
        inhttpproc.addInterceptor(new ResponseConnControl());

        // Set up HTTP protocol processor for outgoing connections
        BasicHttpProcessor outhttpproc = new BasicHttpProcessor();
        outhttpproc.addInterceptor(new RequestContent());
        outhttpproc.addInterceptor(new RequestTargetHost());
        outhttpproc.addInterceptor(new RequestConnControl());
        outhttpproc.addInterceptor(new RequestUserAgent());
        outhttpproc.addInterceptor(new RequestExpectContinue());

        // Initialize cache
        m_cache = new ProxyCache(dbURL, dbName, tblName, dbUsername, dbPassword, dbDriver);

        // Set up outgoing request executor
        HttpRequestExecutor httpexecutor = new HttpRequestExecutor();

        // Set up incoming request handler
        HttpRequestHandlerRegistry reqistry = new HttpRequestHandlerRegistry();
        reqistry.register("*", new ProxyRequestHandler(m_httpparams,
                                                       outhttpproc,
                                                       httpexecutor,
                                                       m_clientSockFact,
                                                       m_cache));

        // Set up the HTTP service
        m_httpservice = new HttpService(inhttpproc,
                                       new DefaultConnectionReuseStrategy(),
                                       new DefaultHttpResponseFactory());
        m_httpservice.setParams(m_httpparams);
        m_httpservice.setHandlerResolver(reqistry);
    }

    /**
     * Create a new bounded server socket using ServerSocketFactory with the
     * given port
     * @throws IOException unable to bind the server socket
     */
    public void bind() throws IOException {
        m_socket = m_servSockFact.createServerSocket(m_port);
    }

    /**
     * Starts the server loop: listens to client requests and executes them. To
     * create new sockets for connecting to servers use ONLY
     * SocketFactory.createSocket(String host, int port) where SocketFactory is
     * the one passed to the constructor.
     */
    public void start() {
        log.info("HttpProxy is listening on port " + m_socket.getLocalPort());

        while (true) {
            Socket clientSock;
            try {
                clientSock = m_socket.accept();
            }
            catch (IOException e) {
                System.err.println("[!] Client socket creation failed.");
                continue;
            }

            DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
            try {
                conn.bind(clientSock, m_httpparams);
            }
            catch (IOException e) {
                System.err.println("[!] Connection with client failed");
                continue;
            }

            log.info("Client from ip: " + clientSock.getInetAddress() + "\n");
            try {

                m_httpservice.handleRequest(conn, new BasicHttpContext(null));

            }
            catch (ConnectionClosedException ex) {
                System.err.println("[!] Client closed connection");
            }
            catch (UnknownHostException e) {
                System.err.println("[!] Unknown destination host");
                sendErr(conn);
            }
            catch (IOException e) {
                System.err.println("[!] " + e.getMessage());
            }
            catch (HttpException ex) {
                System.err.println("[!] HTTP protocol violation: " + ex.getMessage());
                sendErr(conn);
            }
            finally {
                try {
                    conn.shutdown();
                } catch (IOException ignore) {}
            }
        }
    }


    private void sendErr(DefaultHttpServerConnection con) {
        try {
            con.sendResponseHeader(ERR_RESPONSE);
            con.flush();
        }
        catch (Exception e2) {
            System.err.println("[!] Failed to send error respones to client");
        }
    }

    public static void main(String[] args) throws Exception {
        // first arg is the path to the config file
        Properties props = new Properties();
        props.load(new FileInputStream(args[0]));
        Injector injector = Guice.createInjector(new HttpProxyModule(props));
        HttpProxy proxy = injector.getInstance(HttpProxy.class);
        proxy.bind();
        proxy.start();
    }
}
