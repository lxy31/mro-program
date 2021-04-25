package mro.stream.source.eric;


import mro.utils.DateUtil;
import mro.utils.FtpUtil;
import mro.utils.RedisCluster;
import org.apache.commons.io.IOUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import redis.clients.jedis.JedisCluster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author : lijichen
 * @date :  2020/6/9
 * @description: 测试流程 - 文件下载
 */
public class CollectEricFileFtp extends RichParallelSourceFunction<Map<String, String>> {
    private boolean flag = true;
    private static JedisCluster jedis = null;
    private static final long serialVersionUID = 2174904787118597072L;
    private long time_15 = System.currentTimeMillis();
    private static long time_interval = System.currentTimeMillis();
    private Connection conn = null;
    private static final String DRIVER = "com.mysql.jdbc.Driver";
    private static final String URL = "jdbc:mysql://10.76.217.186:3306/mr_info?serverTimezone=UTC";
    private static final String USER = "mr_info";
    private static final String PASSWORD = "mr_info1q#";

    @Override
    public void open(Configuration parameters) throws Exception {
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            System.out.println("Failed to create Mysql connection object A");
        }
        while(true){
            try{
                conn = DriverManager.getConnection(URL, USER, PASSWORD);
            }catch (Exception e){
                continue;
            }
            break;
        }
    }

    /**
     * @return [java.util.List<java.lang.String>, java.util.Map<java.lang.String,java.lang.String>, java.lang.String[], org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext<java.util.Map<java.lang.String,java.lang.String>>, java.util.List<java.lang.String>]
     * @Description //TODO
     * @Date 21:25 2021/4/19
     * @Param [filePathList, mapstr, Ts_str, src, logList]
     **/
    private void downFtpFile(List<String> filePathList, Map<String, String> mapstr, String[] Ts_str, SourceContext<Map<String, String>> src, List<String> logList) {
        String fileName = "";
        String sftpHost = Ts_str[0];
        int sftpPort = Integer.parseInt(Ts_str[1]);
        String sftpUser = Ts_str[2];
        String sftpPass = Ts_str[3];
        int createFileCount = filePathList.size();
        //i为循环因子，遍整个list，j为每次剔除已下载文件后的下一个文件坐标，当成功删除，下一个对象下标前移，j不变，若下载失败，j+1
        for (int i = 0, j = 0; i < createFileCount; i++) {
            fileName = filePathList.get(j);
            /**判断时间*/
            if (System.currentTimeMillis() - time_interval > 900000) {
                time_interval = System.currentTimeMillis();
                break;
            }
            if (System.currentTimeMillis() - DateUtil.toTimestamp(fileName.substring(fileName.length() - 22, fileName.length() - 8), "yyyyMMddHHmmss") > 7200000) {
                filePathList.remove(j);
                continue;
            }

            FTPClient ftpClient = null;
            InputStream isFile = null;
            ByteArrayInputStream bStream = null;
            ZipInputStream zip = null;
            ByteArrayOutputStream baos = null;
            try {
                //进行一个下载
                ftpClient = FtpUtil.loginFTP(sftpHost, sftpPort, sftpUser, sftpPass);
                String dir = fileName.substring(0, fileName.lastIndexOf("/") + 1);
                String file = fileName.substring(fileName.lastIndexOf("/") + 1);
                ftpClient.changeWorkingDirectory(dir);
                isFile = ftpClient.retrieveFileStream(file);//得到下载信息
                if (isFile == null || ftpClient.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
                    j++;
                    continue;
                }
                byte[] bytes = IOUtils.toByteArray(isFile);
                /*将流转化为byte数组字节数组输入流在内存中创建一个字节数组缓冲区，从输入流读取的数据保存在该字节数组缓冲区中*/
                bStream = new ByteArrayInputStream(bytes);
                zip = new ZipInputStream(bStream);
                ZipEntry zipEntry = null;
                while ((zipEntry = zip.getNextEntry()) != null) {
                    mapstr.put("filePath", zipEntry.getName());
                    int lenth = 0;
                    byte[] buf = new byte[4096];
                    baos = new ByteArrayOutputStream();
                    while ((lenth = zip.read(buf)) > 0) {
                        baos.write(buf, 0, lenth);
                    }
                    mapstr.put("dataContent", baos.toString());//字符串
                }
                src.collect(mapstr);
                /*提示回收*/
                logList.add(fileName);
                filePathList.remove(i);
            } catch (Exception e) {
                System.out.println("download error" + e.toString());
                j++;
            } finally {
                try{
                    if (baos != null) {
                        baos.close();
                    }
                    if (zip != null) {
                        zip.close();
                    }
                    if (bStream != null) {
                        bStream.close();
                    }
                    if (isFile != null) {
                        isFile.close();
                    }
                    if (ftpClient != null) {
                        ftpClient.completePendingCommand();
                        ftpClient.disconnect();
                    }
                }catch (Exception ex){
                    System.out.println("CollectFileFtp流关闭异常：" + ex.toString());
                }
            }
        }
    }


    @Override
    public void run(SourceContext<Map<String, String>> sourceContext) throws Exception {
        /**测试保障程序不停*/
        Map<String, String> mapstr = new HashMap<>(50);
        List<String> filePathList = new ArrayList<>(6000);
        List<String> logList = new ArrayList<>(1000000);
        /**查看连接对象是否还存活*/
        if (jedis == null) {
            jedis = RedisCluster.getJedis();
        }
        String Ts_information = CollectEricTools.getServeInfo(jedis);//得到一个队列数据(拿出服务器信息)   数据是什么样的？
        if (!Ts_information.equals("") && Ts_information.contains(";")) {
            String[] Ts_str = Ts_information.split(";");//切割得到ip,端口,用户名,密码
            while (flag) {
                /**为了防止数据库连接对象失效，每隔十五分钟后重新连接一次*/
                if (System.currentTimeMillis() > time_15 + 1000 * 60 * 15) {
                    time_15 = System.currentTimeMillis();
                    if (conn != null) {
                        conn.close();
                    }
                    conn = DriverManager.getConnection(URL, USER, PASSWORD);
                }
                /** 生成文件名称账期 */
                Map<String, String> fileNameDate = CollectEricTools.createFileName();
                String dateStringa = fileNameDate.get("billDate"); // yyyyMMdd
                String dateStrings = fileNameDate.get("billDateHmsS");// 20200421143000

                /**获取自动生成目录资源，使用Map迭代将map数据保存到对应List数组用于后面数据生成*/
                Map<String, String> map = CollectEricTools.extract(conn, Ts_str[0], new HashMap<>(1000), dateStringa); //根据ip和账期从Mysql获取文件绝对路径
                /**按照文件要求生成绝对路径信息的下载信息*/
                map.forEach((k, v) -> {
                    if(k.contains("/")){
                        String[] strs = k.split("/");
                        k = k.replace(strs[strs.length - 3], dateStringa);
                        k = k.replace(k.substring(k.length() - 22, k.length() - 8), dateStrings);
                        filePathList.add(k);
                    }
                });
                downFtpFile(filePathList, mapstr, Ts_str, sourceContext, logList);
                /*>1代表有日志插入map，下面  logList.add(filePathList.get(i));将map插入,过ip地址和data来创建一个文件地址，连接hadoop上面的hdfs，判断hdfs是否存在该文件，如果存在就上传至hdfs*/
                if (logList.size() > 1) {
                    CollectEricTools.toHdfs(logList, Ts_str[0]);
                    logList.clear();
                }
                /**防止下载数太多，造成fgc不过来*/
                Thread.sleep(100);
            }
        }
    }

    @Override
    public void cancel() {
        flag = false;
    }

    @Override
    public void close() throws Exception {
        if (!conn.isClosed()) {
            conn.close();
        }
    }
}
