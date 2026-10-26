package com.tianxiaodudou.clipboardpolice.ui;

/** 一条剪贴板记录。 */
public class ClipRecord {
    public long id;
    public long timestamp;
    /** write / read */
    public String operation = "write";
    public String sourcePackage = "unknown";
    public String content = "";
    public String createdAt = "";
    /** 界面折叠状态，不参与持久化 */
    public boolean expanded;
}
