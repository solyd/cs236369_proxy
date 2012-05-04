package il.technion.cs236369.proxy;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;


//httproxy.db.driver = com.mysql.jdbc.Driver
//httproxy.db.url = jdbc:mysql://127.0.0.1:3306/
//httproxy.db.name = proxy
//httproxy.db.table = cache
//httproxy.db.username = sol
//httproxy.db.password = lol123
//httproxy.net.port = 8080

public class ProxyCache {
    private static Logger log = Logger.getLogger(HttpProxy.class.getName());

    private Connection dbconn;
    private String mCacheTableName;

    public ProxyCache(String dbUrl,
                      String dbName,
                      String tblName,
                      String dbUser,
                      String dbPasswd,
                      String dbDriver) {

        mCacheTableName = tblName;

        try {
            Class.forName(dbDriver);
            Properties p = new Properties();
            p.put("user", dbUser);
            p.put("password", dbPasswd);

            StringBuilder info = new StringBuilder();
            info.append("\n++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
            info.append("Attempting to connect to db with following parameters:\n");
            info.append("\t[DB URL] " + dbUrl + "\n");
            info.append("\t[DB name] " + dbName + "\n");
            info.append("\t[Table name] " + tblName + "\n");
            info.append("\t[User] " + dbUser + "\n");
            info.append("\t[Password] " + dbPasswd + "\n");
            info.append("\t[Driver] " + dbDriver + "\n");
            info.append("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
            log.info(info.toString());


            // Now try to connect
            dbconn = DriverManager.getConnection(dbUrl, p);
            log.info("db connection successfull !");
            dbconn.createStatement().executeQuery("USE " + dbName);
        }
        catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public void printCache(PrintStream stream) {
        if (dbconn == null)
            stream.println("[!] DB connection is closed, can't print cache");

        try {
            Statement s = dbconn.createStatement();
            ResultSet rs = s.executeQuery("SELECT * FROM " + mCacheTableName);
            writeResultSet(stream, rs);
        }
        catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public void close() {
        try {
            dbconn.close();
        }
        catch (SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void writeResultSet(PrintStream stream, ResultSet rs) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
            int i = 0;
            while (rs.next()) {
                sb.append("-- Entry #" + i + " --\n");
                sb.append("[URL]\t" + rs.getString(1) + "\n");
                sb.append("[Headers]:\n" + rs.getString(2));
                sb.append(sb.toString() + "\n");
                sb.append("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
            }

            stream.println(sb.toString());
        }
        catch (SQLException e) {
            e.printStackTrace(System.err);
        }
    }
}


