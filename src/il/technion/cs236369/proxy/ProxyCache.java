package il.technion.cs236369.proxy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;

/**
 *  Connection to database is created and then closed whenever neccessary.
 */
public class ProxyCache {
    private static Logger log = Logger.getLogger(HttpProxy.class.getName());

    private static int MAX_BODYLEN = 65535;
    private static int MAX_URLLEN = 255;

    private Connection    m_dbconn;
    private String        m_tblName;
    private Properties    m_connprop;
    private boolean       m_driverproblem;
    private String        m_dbUrl;
    private String        m_dbName;

    /**
     * Used to return the stored data in the cache.
     */
    public static class CachedItem {
        public final String   m_url;
        public final Header[] m_headers;
        public final byte[]   m_body;

        public CachedItem(String url, Header[] headers, byte[] body) {
            m_url = url;
            m_headers = headers;
            m_body = body;
        }
    }

    public ProxyCache(String dbUrl,
                      String dbName,
                      String tblName,
                      String dbUser,
                      String dbPasswd,
                      String dbDriver) {

        m_dbconn = null;
        m_tblName = tblName;
        try {
            Class.forName(dbDriver);
        }
        catch (ClassNotFoundException e1) {
            log.info("DB driver class not found, won't use cache");
            m_driverproblem = true;
            return;
        }
        m_connprop = new Properties();
        m_connprop.put("user", dbUser);
        m_connprop.put("password", dbPasswd);

        m_driverproblem = false;
        m_dbUrl = dbUrl;
        m_dbName = dbName;
    }

    /**
     * Stores Http response data in the cache.
     * @param url -  the url for which we store the GET response
     * @param headers - http headers of the response
     * @param body - the body of the response
     */
    public void store(String url, Header[] headers, byte[] body) {
        if (body.length > MAX_BODYLEN || url.length() > MAX_URLLEN)
            return;

        if (!connect())
            return;

        log.info("Creating/updating cache entry for url: " + url);
        try {
            PreparedStatement stmt = m_dbconn.prepareStatement("INSERT INTO " + m_tblName +
                                                               " (url,headers,body) VALUES (?,?,?) " +
                                                               "ON DUPLICATE KEY UPDATE headers=?, body=?");
            String headersStr = headersToString(headers);
            stmt.setString(1, url);
            stmt.setString(2, headersStr);
            stmt.setBytes(3, body);
            stmt.setString(4, headersStr);
            stmt.setBytes(5, body);

            stmt.executeUpdate();
        }
        catch (SQLException e) {
            log.info("Error creating/updating cache entry for url: " + url);
        }
        finally {
            disconnect();
        }
    }

    /**
     * @param url - check if cache stores the response for GET request for this url
     * @return the cached response data for this url if exists. If doesn't exist,
     * we return an empty CachedItem.
     */
    public CachedItem retrieve(String url) {
        CachedItem res = new CachedItem(url, new Header[0], new byte[0]);

        if (!connect())
            return res;

        log.info("Retrieving cache entry for url: " + url);
        try {
            PreparedStatement stmt = m_dbconn.prepareStatement("SELECT * FROM " + m_tblName + " WHERE url=?");
            stmt.setString(1, url);

            ResultSet resset = stmt.executeQuery();
            resset.next();
            String headersStr = resset.getString(2);
            byte[] body = resset.getBytes(3);

            return new CachedItem(url, strToHeaders(headersStr), body);
        }
        catch (SQLException e) {
            log.info("Retrieval of cache entry for url: " + url + " failed!");
        }
        finally {
            disconnect();
        }

        return res;
    }

    /**
     * Given url, we remove the cache entry that stores the response for this url.
     * @param url
     */
    public void invalidate(String url) {
        if (!connect())
            return;

        log.info("Invalidating cache entry for url: " + url);
        try {
            PreparedStatement stmt = m_dbconn.prepareStatement("DELETE FROM " + m_tblName + " WHERE url=?");
            stmt.setString(1, url);
            stmt.executeUpdate();
        }
        catch (SQLException e) {
            log.info("Failed to invalidate cache entry of url: " + url);
        }
        finally {
            disconnect();
        }
    }

    /**
     * Check if there is a cached response to GET request of url
     * @param url
     * @return true if there is cached response for the url, false otherwise.
     */
    public boolean contains(String url) {
        if (!connect())
            return false;

        try {
            PreparedStatement stmt = m_dbconn.prepareStatement("SELECT COUNT(*) FROM " + m_tblName + " WHERE url=?");
            stmt.setString(1, url);
            ResultSet resset = stmt.executeQuery();
            resset.next();
            if (Integer.parseInt(resset.getString(1)) > 0)
                return true;

            return false;
        }
        catch (SQLException ignore) { return false; }
        finally {
            disconnect();
        }
    }

    // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    /**
     * Create a connection to the cache database.
     * @return true if connection is successful, false otherwise.
     */
    private boolean connect() {
        if (m_driverproblem)
            return false;

        try {
            m_dbconn = DriverManager.getConnection(m_dbUrl, m_connprop);
            m_dbconn.createStatement().executeQuery("USE " + m_dbName);
        }
        catch (Exception e) {
            System.err.println("[!] Problem connecting/using cache DB - will continue without using cache");
            m_dbconn = null;
        }

        return m_dbconn != null;
    }

    /**
     * Close the connection to the database, if exists.
     */
    private void disconnect() {
        if (m_dbconn == null)
            return;

        try {
            m_dbconn.close();
        }
        catch (SQLException ignore) {}

        m_dbconn = null;
    }

    /**
     * Given an array of http headers we trasform them into a string - to be stored in the cache table.
     * They format is:
     *      header#1 type: header#1 value\r\n
     *      header#2 type: header#2 value\r\n
     * @param headers
     * @return string value representing all headers in the array.
     */
    private String headersToString(Header[] headers) {
        StringBuilder res = new StringBuilder();
        for (Header h : headers)
            res.append(h.toString() + "\r\n");

        return res.toString();
    }


    /**
     * Given a string that represents an array of headers we build object from it.
     * @param headersStr
     * @return array of headers constructed from the string.
     */
    private Header[] strToHeaders(String headersStr) {
        ArrayList<BasicHeader> res = new ArrayList<BasicHeader>();

        String[] headerLines = headersStr.split("\\r\\n");
        for (String hdrline : headerLines) {
            String[] splithdr = hdrline.split(":");
            res.add(new BasicHeader(splithdr[0], splithdr[1]));
        }

        // toArray() doesn't work...
        Header[] resarr = new Header[res.size()];
        for (int i = 0, len = res.size(); i < len; ++i)
            resarr[i] = res.get(i);

        return resarr;
    }
}
