package il.technion.cs236369.proxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpException;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
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
    private static Logger log = Logger.getLogger(HttpProxy.class.getName());

    private ServerSocketFactory mServSockFact;
    private SocketFactory       mSockFact;
    private int                 mServPort;
    private ServerSocket        mServSock;
    private HttpParams          mHttpParams;
    private HttpService         mHttpService;

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

        mSockFact = sockFactory;
        mServSockFact = srvSockFactory;
        mServPort = port;


        // setup http request handling
        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        mHttpParams = new SyncBasicHttpParams();
        mHttpParams.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000);
        mHttpParams.setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024);
        mHttpParams.setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false);
        mHttpParams.setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);
        mHttpParams.setParameter(CoreProtocolPNames.ORIGIN_SERVER, "HttpComponents/1.1");

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

        // Set up outgoing request executor
        HttpRequestExecutor httpexecutor = new HttpRequestExecutor();

        // Set up incoming request handler
        HttpRequestHandlerRegistry reqistry = new HttpRequestHandlerRegistry();
        reqistry.register("*", new ProxyRequestHandler(mHttpParams,
                                                       outhttpproc,
                                                       httpexecutor,
                                                       mSockFact));

        // Set up the HTTP service
        mHttpService = new HttpService(inhttpproc,
                                       new DefaultConnectionReuseStrategy(),
                                       new DefaultHttpResponseFactory());
        mHttpService.setParams(mHttpParams);
        mHttpService.setHandlerResolver(reqistry);

        // [TODO] complete init of proxy with db stuff
        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    }

    /**
     * Create a new bounded server socket using ServerSocketFactory with the
     * given port
     * @throws IOException unable to bind the server socket
     */
    public void bind() throws IOException {
        mServSock = mServSockFact.createServerSocket(mServPort);
    }

    /**
     * Starts the server loop: listens to client requests and executes them. To
     * create new sockets for connecting to servers use ONLY
     * SocketFactory.createSocket(String host, int port) where SocketFactory is
     * the one passed to the constructor.
     */
    public void start() {
        while (true) {
            try {
                // Set up incoming HTTP connection
                Socket clientSock = mServSock.accept();
                DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
                log.info("Client from ip: " + clientSock.getInetAddress() + "\n");
                conn.bind(clientSock, mHttpParams);

                HttpContext context = new BasicHttpContext(null);
                try {
                    mHttpService.handleRequest(conn, context);
                }
                catch (ConnectionClosedException ex) {
                    System.err.println("Client closed connection");
                }
                catch (IOException ex) {
                    System.err.println("I/O error: " + ex.getMessage());
                }
                catch (HttpException ex) {
                    System.err.println("Unrecoverable HTTP protocol violation: " + ex.getMessage());
                }
                finally {
                    try {
                        conn.shutdown();
                    } catch (IOException ignore) {}
                }
            }
            catch (IOException e) {
                e.printStackTrace(System.err);
            }
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
