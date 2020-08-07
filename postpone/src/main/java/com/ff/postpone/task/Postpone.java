package com.ff.postpone.task;


import com.ff.postpone.common.*;
import com.ff.postpone.constant.*;
import com.ff.postpone.util.*;
import net.sf.json.JSONObject;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.BasicCookieStore;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @Author Demo-Liu
 * @Date 2019/3/20 14:29
 * @description
 */
@Component
public class Postpone {

    private static Logger log = LoggerFactory.getLogger(Postpone.class);


    //这里建议设置为30分钟每次
    @Scheduled(cron = "0 0/30 * * * ? ")
    public void postpone(){
        //获取所有云账号配置
        List<Map<String, String>> cloudServers = Profile.cloudServers;

        //邮箱配置
        MailUtil mailUtil = MailUtil.MailUtilBuilder.getBuilder()
                .setHost(Profile.MAIL_SERVER_HOST)
                .setPort(Profile.MAIL_SERVER_PORT)
                .setPassword(Profile.MAIL_PASSWORD)
                .setReceiveUser(Profile.MAIL_RECEIVE_USER)
                .build();


        String username = null;
        String type;
        CloudInfo cloudInfo;
        String cloudName = null;
        HttpClient httpClient;
        String ukLog = null;
        //循环检查免费服务器状态
        for (Map<String, String> serverInfo : cloudServers) {
            try{
                httpClient = HttpUtil.getHttpClient(new BasicCookieStore());
                username = serverInfo.get(Constans.CLOUD_USERNAME);
                type = serverInfo.get(Constans.CLOUD_TYPE);
                cloudInfo = CloudInfo.getCloudInfo(type);
                cloudName = cloudInfo.getCloudName();

                ukLog = CommonCode.getUKLog(username, cloudName);


                //调用登录接口查看状态
                String status = loginAndCheck(httpClient, mailUtil, serverInfo, cloudInfo);

                if(status != null && "1".equals(status)){
                    //发送博客
                    String blogUrl = BlogGit.sendCustomBlogByType(type);
                    //持久化至文件
                    Map<String, String> map = Profile.userInfos.get(CommonCode.getUserKey(username, type));
                    map.put("blogUrl", blogUrl);
                    CommonCode.userInfosPermanent();

                    //生成截图
                    boolean pic = createPic(blogUrl);
                    //如果创建成功 开始发送延期请求
                    if(pic){
                        postBlogInfo(httpClient, mailUtil, blogUrl, serverInfo, cloudInfo);
                    }else{
                        mailUtil.sendMail(cloudName+"账号:"+username+",网页截图生成失败", "blog Url: "+ blogUrl);
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                log.error("{},延期过程出错!!!", ukLog);
                mailUtil.sendMail(cloudName + "账号: "+username+",延期过程出错",e.getMessage());
            }
        }

    }

        /**
         * 登录和检查状态接口
         */
    public String loginAndCheck(HttpClient httpClient, MailUtil mailUtil, Map<String,String> serverInfo, CloudInfo cloudInfo) throws Exception {
        //用户名密码
        String username = serverInfo.get(Constans.CLOUD_USERNAME);
        String password = serverInfo.get(Constans.CLOUD_PASSWORD);
        //服务器类型名称
        String cloudName = cloudInfo.getCloudName();
        String type = cloudInfo.getType();


        //持久化信息
        Map<String, String> userInfo = Profile.userInfos.get(CommonCode.getUserKey(username, type));
        String nextTime = userInfo.get(CloudData.NEXT_TIME);

        String ukLog = CommonCode.getUKLog(username, cloudName);

        String status = null;
        //1. 检查是否到期
        if(StringUtil.isEmpty(nextTime) || CommonCode.isExpire(nextTime)){
            log.info("{}开始登录...", ukLog);

            //2. 调用登录接口
            JSONObject loginJson = JSONObject.fromObject(
                    HttpUtil.getPostRes(httpClient,
                            cloudInfo.getLoginUri(),
                            Params.getYunLogin(username, password))
            );
            log.info("{}登录接口返回:{}", ukLog, loginJson.toString());

            if(CloudData.LOGIN_SUCCESS.equals(loginJson.getString(CloudData.LOGIN_STATUS))){
                //3. 登录成功 调用状态接口
                log.info("{}登录成功,开始检查免费服务器状态!!!", ukLog);
                JSONObject statusJson = JSONObject.fromObject(
                        HttpUtil.getPostRes(httpClient,
                                cloudInfo.getBusUri(),
                                Params.getFreeStatus()));
                log.info("{}检查服务器状态返回:{}", ukLog, statusJson.toString());
                statusJson = statusJson.getJSONObject(CloudData.STATUS_DATA);
                status = statusJson.containsKey(CloudData.DELAY_STATUS1) ? statusJson.getString(CloudData.DELAY_STATUS1) : statusJson.getString(CloudData.DELAY_STATUS2);


                //审核状态接口
                JSONObject checkJson = JSONObject.fromObject(
                        HttpUtil.getPostRes(httpClient,
                                cloudInfo.getBusUri(),
                                Params.getCheckStatus()));
                log.info("{}延期记录接口返回:{}", ukLog, checkJson.toString());

                switch (status) {
                    case "1": //已到审核期
                        CommonCode.checkCheckStatus(checkJson, ukLog, userInfo.get("blogUrl"));
                        log.info("{},已到审核期!!!", ukLog);
                        break;
                    case "0": //未到审核期
                        CommonCode.checkCheckStatus(checkJson, ukLog, userInfo.get("blogUrl"));
                        String next_time = statusJson.getString(CloudData.NEXT_TIME);
                        if(!next_time.equals(StringUtil.isEmpty(nextTime) ? "" : next_time)){
                            mailUtil.sendMail(cloudName + "账号: "+username+"下次执行时间"+next_time, statusJson.toString());
                        }
                        //持久化到文件
                        userInfo.put(CloudData.NEXT_TIME, next_time);
                        CommonCode.userInfosPermanent();
                        log.info("{}未到审核期,下次审核开始时间:{}", ukLog, next_time);
                        break;
                    default :
                        log.info("{}正在审核!!!", ukLog);
                }
            }else{
                log.info("{}登录失败!!!",ukLog);
                mailUtil.sendMail(cloudName + "账号: "+username+", 登录失败!!!",loginJson.toString());
            }
        }else{
            log.info("{}未到期", ukLog);
        }
        return status;
    }


    /**
     * 生成网页截图
     * @param blogUrl
     * @return
     * @throws Exception
     */
    public boolean createPic(String blogUrl) throws Exception {
        log.info("开始创建文件...");
        StringBuilder sb = new StringBuilder();
        String BLANK = "  ";
        String osName = System.getProperty("os.name");

        if(StringUtil.isEmpty(Profile.PJ_EXEC)){
            if(osName.contains("windows")){
                sb.append(Constans.PJ_WIN.substring(1)).append(BLANK)
                        .append(Constans.PIC_JS.substring(1)).append(BLANK);
            }else if(osName.contains("linux")){
                sb.append(Constans.PJ_LINUX_X86_64).append(BLANK)
                        .append(Constans.PIC_JS).append(BLANK);
            }else{
                log.info("未配置phantomJs: {}", osName);
                throw new Exception("未配置phantomJs: "+osName);
            }
        }else{
            sb.append(Profile.PJ_EXEC).append(BLANK);
        }

        sb.append(blogUrl).append(BLANK)
        .append(Profile.PJ_PIC_PATH);


        //执行一次删除防止上次任务残留
        File file = FileUtil.deleteFile(Profile.PJ_PIC_PATH);

        Runtime rt = Runtime.getRuntime();
        try {
            rt.exec(sb.toString());
            Thread.sleep(20000);
            int i = 0;
            while(!file.exists()){
                log.info("文件未创建成功,等待10秒...");
                i++;
                if(i==20){
                    log.info("文件创建失败: {}", blogUrl);
                    return false;
                }
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            log.info("文件创建失败: {}", blogUrl);
            e.printStackTrace();
            return false;
        }
        log.info("文件创建成功!!!");
        return true;
    }


    /**
     * 提交延期博客信息
     * @param httpClient
     * @param mailUtil
     * @param blogUrl
     * @param serverInfo
     * @param cloudInfo
     * @throws IOException
     * @throws GitAPIException
     */
    public void postBlogInfo(HttpClient httpClient, MailUtil mailUtil, String blogUrl, Map<String,String> serverInfo, CloudInfo cloudInfo) throws IOException, GitAPIException {
        String username = serverInfo.get(Constans.CLOUD_USERNAME);
        String cloudName = cloudInfo.getCloudName();

        String ukLog = CommonCode.getUKLog(username, cloudName);

        File file = FileUtil.deleteFile(Profile.PJ_PIC_PATH);

        String postRes = HttpUtil.getPostRes(httpClient, cloudInfo.getBusUri(), Params.getBlogInfo(cloudInfo,file,blogUrl).build());
        JSONObject json = JSONObject.fromObject(postRes);
        log.info("{}提交延期记录返回结果: {}", ukLog, json.toString());

        if(CloudData.BLOG_SUCCESS.equals(json.getString(CloudData.BLOG_STATUS))){
            log.info("{}提交延期记录成功!!!", ukLog);
        }else{
            log.info("{}提交延期记录失败,删除发布博客!!!", ukLog);
            mailUtil.sendMail(cloudName+"账号:"+username+"发送延期博客失败", json.toString());
            BlogGit.deleteBlog(blogUrl);
        }
        file.delete();
    }
}
