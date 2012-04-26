package il.technion.cs236369.proxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.name.Named;

public class HttpProxy {

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

    @Inject
    HttpProxy(SocketFactory sockFactory, ServerSocketFactory srvSockFactory,
              @Named("httproxy.net.port") int port,
              @Named("httproxy.db.url") String dbURL,
              @Named("httproxy.db.name") String dbName,
              @Named("httproxy.db.table") String tblName,
              @Named("httproxy.db.username") String dbUsername,
              @Named("httproxy.db.password") String dbPassword,
              @Named("httproxy.db.driver") String dbDriver) {

        // YOUR IMPLEMENTATION HERE
    }

    /**
     * Create a new bounded server socket using ServerSocketFactory with the
     * given port
     * @throws IOException unable to bind the server socket
     */
    public void bind() throws IOException {
        // YOUR IMPLEMENTATION HERE
    }

    /**
     * Starts the server loop: listens to client requests and executes them. To
     * create new sockets for connecting to servers use ONLY
     * SocketFactory.createSocket(String host, int port) where SocketFactory is
     * the one passed to the constructor.
     */
    public void start() {
        // YOUR IMPLEMENTATION HERE
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
