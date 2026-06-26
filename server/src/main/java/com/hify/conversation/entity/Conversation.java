package com.hify.conversation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 会话表 {@code conversation} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * app_id/user_id 跨模块弱引用（只存 id，不建外键）。
 */
@TableName("conversation")
public class Conversation extends BaseEntity {

    private Long appId;
    private Long userId;
    private String title;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
