package org.Kagan.core;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import org.Kagan.config.Configure;
import org.Kagan.config.WebsiteConfigure;
import org.Kagan.interfaces.IPageInfo;
import org.Kagan.util.Db;
import org.Kagan.util.StringKit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.alibaba.druid.pool.DruidPooledConnection;

public class Indexer implements Runnable {
    
    private final IPageInfo handler;
    private final WebsiteConfigure wc;
    private final Thread[] readThreads;
    private final BlockingDeque<String> deque;
    private final BlockingQueue<PageInfo> queue;
    private final IndexHandler[] indexHandlers;
    private volatile static boolean closed = false;
    private static final int THREAD_SLEEP_TIME = 500;
    
    public Indexer(WebsiteConfigure wc, BlockingDeque<String> deque, BlockingQueue<PageInfo> queue, IPageInfo handler) {
        this.wc      = wc;
        this.queue   = queue;
        this.deque   = deque;
        this.handler = handler;
        this.readThreads = new Thread[Configure.readThreads];
        this.indexHandlers = new IndexHandler[Configure.readThreads];
    }
    
    @Override
    public String toString() {
        return String.format("Deque Size : %d", deque.size());
    }
    
    public void shutdown() {
        closed = true;
        for (IndexHandler idxHandler : indexHandlers) {
            idxHandler.shutdown();
        }
    }
    
    @Override
    public void run() {
        try {
            indexPage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void indexPage() throws InterruptedException {
        String[] hashKeys;
        Map<String, String> map;
        
        for (int i = 0; i < readThreads.length; i++) {
            indexHandlers[i] = new IndexHandler(deque, queue, handler);
            readThreads[i] = new Thread(indexHandlers[i]);
            readThreads[i].start();
        }
        
        Document doc = parseHtml(wc.getUrl());
        while (!closed) {
            if (doc == null) {
                doc = parseHtml(wc.getUrl());
            } else {
                map = splitUrl(doc.html());
                hashKeys = getValidHashKey(map.keySet());
                if (hashKeys != null) {
                    for (int i = 1; i < hashKeys.length; i++) {
                        deque.putFirst(map.get(hashKeys[i]));
                    }
                    addHashKeyToDb(hashKeys);
                } else {
                    doc = parseHtml(wc.getUrl());
                    Thread.sleep(THREAD_SLEEP_TIME);
                    continue;
                }
                
                String url = map.get(hashKeys[0]);
                if (url != null && (doc = parseHtml(url)) != null) {
                    // Get page info and then add in queue
                    queue.put(getPageInfo(StringKit.sha1(url), url, doc));
                }
                
                Thread.sleep(THREAD_SLEEP_TIME);
            }
            
        }
        
    }
    
    public static Document parseHtml(String url) {
        Document doc = null;
        try {
            doc = Jsoup.connect(url).timeout(5000).get();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return doc;
    }
    
    private Map<String, String> splitUrl(String html) {
        String url;
        Map<String, String> map = new LinkedHashMap<String, String>();
        Matcher matcher = wc.getRegex().matcher(html);
        while (matcher.find()) {
            url = matcher.group(1);
            map.put(StringKit.sha1(url), url);
        }
        return map;
    }
    
    private PageInfo getPageInfo(String hashKey, String url, Document doc) {
        return handler.getPageInfo(hashKey, url, doc);
    }
    
    private boolean addHashKeyToDb(String[] hashKeys) {
        boolean status = false;
        PreparedStatement pstmt = null;
        DruidPooledConnection conn = null;
        String sql = String.format("INSERT INTO %s ( hashKey ) VALUES ( ? )", Configure.indexTable);
        
        try {
            conn = Db.getConnection();
            pstmt = conn.prepareStatement(sql);
            
            for (String key : hashKeys) {
                pstmt.setString(1, key);
                pstmt.addBatch();
            }
            
            pstmt.executeBatch();
            status = true;
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Db.close(conn, pstmt);
        }
        
        return status;
    }
    
    private String[] getValidHashKey(Set<String> keySet) {
        
        if (keySet.size() == 0) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        for (String key : keySet) {
            String k = String.format("'%s'", key);
            if (sb.length() == 0) {
                sb.append(k);
            } else {
                sb.append(",").append(k);
            }
        }
        
        try {
            String sql = String.format("SELECT hashKey FROM %s WHERE hashKey IN ( %s ) ORDER BY id DESC", Configure.indexTable, sb.toString());
            List<Map<String, Object>> result = Db.query(sql);
            
            List<String> urls = new ArrayList<String>();
            for (Map<String, Object> r : result) {
                urls.add(String.valueOf(r.get("hashKey")));
            }
            keySet.removeAll(urls);
        } catch (Exception e) {
            return null;
        }
        
        return keySet.size() > 0 ? keySet.toArray(new String[keySet.size()]) : null;
    }
    
    static class IndexHandler implements Runnable {
        
        private IPageInfo handler;
        private BlockingDeque<String> deque;
        private BlockingQueue<PageInfo> queue;
        private volatile static boolean closed = false;
        
        public IndexHandler(BlockingDeque<String> deque, BlockingQueue<PageInfo> queue, IPageInfo handler) {
            this.deque   = deque;
            this.queue   = queue;
            this.handler = handler;
        }
        
        private PageInfo getPageInfo(String hashKey, String url, Document doc) {
            return handler.getPageInfo(hashKey, url, doc);
        }

        @Override
        public void run() {
            try {
                Document doc;
                while (!closed) {
                    String url = deque.pollLast(5L, TimeUnit.SECONDS);
                    if (url != null && (doc = parseHtml(url)) != null) {
                        queue.put(getPageInfo(StringKit.sha1(url), url, doc));
                        Thread.sleep(THREAD_SLEEP_TIME);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        public void shutdown() {
            closed = true;
        }
        
    }

}
