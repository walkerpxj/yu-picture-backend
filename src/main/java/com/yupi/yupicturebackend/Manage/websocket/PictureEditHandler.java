package com.yupi.yupicturebackend.Manage.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.yupi.yupicturebackend.Manage.websocket.disruptor.PictureEditEventProducer;
import com.yupi.yupicturebackend.Manage.websocket.model.PictureEditRequestMessage;
import com.yupi.yupicturebackend.Manage.websocket.model.PictureEditResponseMessage;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureEditActionEnum;
import com.yupi.yupicturebackend.model.enums.PictureEditMessageTypeEnum;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片编辑 WebSocket 处理器
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PictureEditHandler extends TextWebSocketHandler {

    private final UserService userService;

    private final PictureService pictureService;

    private final PictureEditEventProducer pictureEditEventProducer;

    // 每张图片的编辑状态，key: pictureId, value: 当前正在编辑的用户 ID
    private final Map<Long, Long> pictureEditingUsers = new ConcurrentHashMap<>();

    // 保存所有连接的会话，key: pictureId, value: 用户会话集合
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        // 保存会话到集合中
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        pictureSessions.putIfAbsent(pictureId, ConcurrentHashMap.newKeySet());
        pictureSessions.get(pictureId).add(session);
        // 构造响应，发送加入编辑的消息通知
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("用户 %s 加入编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setPictureId(pictureId);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        // 给新连接同步当前图片编辑状态
        sendCurrentEditState(session, pictureId);
        // 通知其他用户当前有新连接加入编辑
        broadcastToPicture(pictureId, pictureEditResponseMessage, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("收到 WS 文本消息: {}", message.getPayload());
        super.handleTextMessage(session, message);
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        PictureEditRequestMessage pictureEditRequestMessage;
        try {
            // 获取消息内容，将 JSON 转换为 PictureEditRequestMessage
            pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
        } catch (Exception e) {
            log.warn("WS 消息格式错误: {}", message.getPayload(), e);
            PictureEditResponseMessage responseMessage = new PictureEditResponseMessage();
            responseMessage.setType(PictureEditMessageTypeEnum.ERROR.getValue());
            responseMessage.setMessage("消息格式错误");
            responseMessage.setPictureId(pictureId);
            responseMessage.setEditing(pictureEditingUsers.containsKey(pictureId));
            responseMessage.setUser(userService.getUserVO(user));
            sendToSession(session, responseMessage);
            return;
        }

        // 根据消息类型处理消息（生产消息到 Disruptor 环形队列中）
        pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
    }

    public void handleEditActionMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 正在编辑的用户
        Long editingUserId = pictureEditingUsers.get(pictureId);
        String editAction = pictureEditRequestMessage.getEditAction();
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            log.error("无效的编辑动作");
            return;
        }
        // 确认是当前的编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 构造响应，发送具体操作的通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_ACTION.getValue());
            String message = String.format("%s 执行 %s", user.getUserName(), actionEnum.getText());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setPictureId(pictureId);
            pictureEditResponseMessage.setEditing(true);
            pictureEditResponseMessage.setEditAction(editAction);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 广播给除了当前客户端之外的其他用户，否则会造成重复编辑
            broadcastToPicture(pictureId, pictureEditResponseMessage, session);
        }
    }

    public void handleExitEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 正在编辑的用户
        Long editingUserId = pictureEditingUsers.get(pictureId);
        // 确认是当前的编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 移除用户正在编辑该图片
            pictureEditingUsers.remove(pictureId);
            // 构造响应，发送退出编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
            String message = String.format("用户 %s 退出编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setPictureId(pictureId);
            pictureEditResponseMessage.setEditing(false);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
            broadcastCurrentEditState(pictureId);
        }
    }

    public void handleEnterEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 没有用户正在编辑该图片，才能进入编辑
        if (!pictureEditingUsers.containsKey(pictureId)) {
            // 设置用户正在编辑该图片
            pictureEditingUsers.put(pictureId, user.getId());
            // 构造响应，发送加入编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
            String message = String.format("用户 %s 开始编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setPictureId(pictureId);
            pictureEditResponseMessage.setEditing(true);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 广播给所有用户
            broadcastToPicture(pictureId, pictureEditResponseMessage);
            return;
        }
        Long editingUserId = pictureEditingUsers.get(pictureId);
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_DENIED.getValue());
        pictureEditResponseMessage.setPictureId(pictureId);
        pictureEditResponseMessage.setEditing(true);
        if (editingUserId != null) {
            User editingUser = userService.getById(editingUserId);
            if (editingUser != null) {
                pictureEditResponseMessage.setUser(userService.getUserVO(editingUser));
                pictureEditResponseMessage.setMessage(String.format("当前由 %s 正在编辑，请稍后重试", editingUser.getUserName()));
            } else {
                pictureEditResponseMessage.setMessage("当前有人正在编辑，请稍后重试");
            }
        } else {
            pictureEditResponseMessage.setMessage("当前有人正在编辑，请稍后重试");
        }
        sendToSession(session, pictureEditResponseMessage);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 移除当前用户的编辑状态
        handleExitEditMessage(null, session, user, pictureId);
        // 删除会话
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (sessionSet != null) {
            sessionSet.remove(session);
            if (sessionSet.isEmpty()) {
                pictureSessions.remove(pictureId);
            }
        }
        // 通知其他用户，该用户已经离开编辑
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("%s 离开编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setPictureId(pictureId);
        pictureEditResponseMessage.setEditing(pictureEditingUsers.containsKey(pictureId));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    public void handlePingMessage(WebSocketSession session, User user, Long pictureId) throws IOException {
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.PONG.getValue());
        pictureEditResponseMessage.setMessage("pong");
        pictureEditResponseMessage.setPictureId(pictureId);
        pictureEditResponseMessage.setEditing(pictureEditingUsers.containsKey(pictureId));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        sendToSession(session, pictureEditResponseMessage);
    }

    private void sendCurrentEditState(WebSocketSession session, Long pictureId) throws IOException {
        sendToSession(session, buildCurrentEditStateMessage(pictureId));
    }

    private void broadcastCurrentEditState(Long pictureId) throws IOException {
        broadcastToPicture(pictureId, buildCurrentEditStateMessage(pictureId));
    }

    private PictureEditResponseMessage buildCurrentEditStateMessage(Long pictureId) {
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.STATE_SYNC.getValue());
        pictureEditResponseMessage.setPictureId(pictureId);
        fillPictureState(pictureEditResponseMessage, pictureId);
        Long editingUserId = pictureEditingUsers.get(pictureId);
        if (editingUserId == null) {
            pictureEditResponseMessage.setEditing(false);
            pictureEditResponseMessage.setMessage("当前无人编辑");
            return pictureEditResponseMessage;
        }
        User editingUser = userService.getById(editingUserId);
        pictureEditResponseMessage.setEditing(true);
        if (editingUser != null) {
            pictureEditResponseMessage.setMessage(String.format("当前由 %s 正在编辑", editingUser.getUserName()));
            pictureEditResponseMessage.setUser(userService.getUserVO(editingUser));
        } else {
            pictureEditResponseMessage.setMessage("当前有人正在编辑");
        }
        return pictureEditResponseMessage;
    }

    private void fillPictureState(PictureEditResponseMessage pictureEditResponseMessage, Long pictureId) {
        Picture picture = pictureService.getById(pictureId);
        if (picture != null) {
            pictureEditResponseMessage.setPictureUrl(picture.getUrl());
        }
    }

    private void sendToSession(WebSocketSession session, PictureEditResponseMessage pictureEditResponseMessage) throws IOException {
        if (session != null && session.isOpen()) {
            ObjectMapper objectMapper = new ObjectMapper();
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            objectMapper.registerModule(module);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pictureEditResponseMessage)));
        }
    }

    /**
     * 广播给除excludeSession外的所有人
     * @param pictureId
     * @param pictureEditResponseMessage
     * @param excludeSession
     * @throws IOException
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage, WebSocketSession excludeSession) throws IOException {
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isNotEmpty(sessionSet)) {
            // 创建 ObjectMapper
            ObjectMapper objectMapper = new ObjectMapper();
            // 配置序列化：将 Long 类型转为 String，解决丢失精度问题
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance); // 支持 long 基本类型
            objectMapper.registerModule(module);
            // 序列化为 JSON 字符串
            String message = objectMapper.writeValueAsString(pictureEditResponseMessage);
            TextMessage textMessage = new TextMessage(message);
            for (WebSocketSession session : sessionSet) {
                // 排除掉的 session 不发送
                if (excludeSession != null && session.equals(excludeSession)) {
                    continue;
                }
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        }
    }

    /**
     * 广播给所有用户
     * @param pictureId
     * @param pictureEditResponseMessage
     * @throws IOException
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage) throws IOException {
        broadcastToPicture(pictureId, pictureEditResponseMessage, null);
    }
}
