package cc.blynk.server.handlers.auth;

import cc.blynk.common.model.messages.protocol.LoginMessage;
import cc.blynk.server.auth.User;
import cc.blynk.server.auth.UserRegistry;
import cc.blynk.server.auth.session.SessionsHolder;
import cc.blynk.server.exceptions.IllegalCommandException;
import cc.blynk.server.exceptions.InvalidTokenException;
import cc.blynk.server.exceptions.UserNotAuthenticated;
import cc.blynk.server.exceptions.UserNotRegistered;
import cc.blynk.server.handlers.DefaultExceptionHandler;
import cc.blynk.server.utils.FileManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import static cc.blynk.common.enums.Response.OK;
import static cc.blynk.common.model.messages.MessageFactory.produce;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public class LoginHandler extends SimpleChannelInboundHandler<LoginMessage> implements DefaultExceptionHandler {

    protected final FileManager fileManager;
    protected final UserRegistry userRegistry;
    protected final SessionsHolder sessionsHolder;

    public LoginHandler(FileManager fileManager, UserRegistry userRegistry, SessionsHolder sessionsHolder) {
        this.fileManager = fileManager;
        this.userRegistry = userRegistry;
        this.sessionsHolder = sessionsHolder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LoginMessage message) throws Exception {
        String[] messageParts = message.body.split(" ", 2);

        if (messageParts.length == 0) {
            throw new IllegalCommandException("Wrong income message format.", message.id);
        }

        if (messageParts.length == 1) {
            hardwareLogin(ctx, message.id, messageParts[0]);
        } else if (messageParts.length == 2) {
            appLogin(ctx, message.id, messageParts[0], messageParts[1]);
        }


        ctx.writeAndFlush(produce(message.id, OK));
    }

    ///todo optimize/simplify
    private void hardwareLogin(ChannelHandlerContext ctx, int messageId, String token) {
        User user = userRegistry.getByToken(token);

        if (user == null) {
            throw new InvalidTokenException(String.format("Hardware token is invalid. Token '%s', %s", token, ctx.channel()), messageId);
        }

        sessionsHolder.addHardwareChannelToGroup(user, ctx.channel(), messageId);

        log.info("Adding hardware channel with id {} to userGroup {}.", ctx.channel(), user.getName());
    }

    private void appLogin(ChannelHandlerContext ctx, int messageId, String username, String pass) {
        String userName = username.toLowerCase();
        User user = userRegistry.getByName(userName);

        if (user == null) {
            throw new UserNotRegistered(String.format("User not registered. Username '%s', %s", userName, ctx.channel()), messageId);
        }

        //todo fix pass validation
        if (!user.getPass().equals(pass)) {
            throw new UserNotAuthenticated(String.format("User credentials are wrong. Username '%s', %s", userName, ctx.channel()), messageId);
        }

        sessionsHolder.addAppChannelToGroup(user, ctx.channel(), messageId);

        log.info("Adding app channel with id {} to userGroup {}.", ctx.channel(), user.getName());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
       handleException(ctx, cause);
    }
}