/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017. Diorite (by Bartłomiej Mazur (aka GotoFinal))
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.diorite.core.protocol.connection;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;

import com.google.common.collect.Queues;

import org.apache.commons.lang3.ArrayUtils;

import org.diorite.chat.ChatMessage;
import org.diorite.core.DioriteCore;
import org.diorite.core.protocol.ProtocolVersion;
import org.diorite.core.protocol.connection.internal.Packet;
import org.diorite.core.protocol.connection.internal.ProtocolState;
import org.diorite.core.protocol.connection.internal.QueuedPacket;
import org.diorite.core.protocol.connection.internal.ServerboundPacketListener;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public abstract class ActiveConnection extends SimpleChannelInboundHandler<Packet<? super ServerboundPacketListener>>
{
    protected final Queue<QueuedPacket> packetQueue = Queues.newConcurrentLinkedQueue();

    protected final DioriteCore       dioriteCore;
    protected final InetSocketAddress serverAddress;
    protected final int               playerTimeout;

    @Nullable protected Channel                   channel;
    @Nullable protected SocketAddress             address;
    protected           ServerboundPacketListener packetListener;
    @Nullable protected ChatMessage               disconnectMessage;
    protected           ProtocolVersion<?>        protocolVersion;

    protected volatile boolean closed    = false;
    protected volatile boolean preparing = true;

    public ActiveConnection(DioriteCore dioriteCore, InetSocketAddress serverAddress, ServerboundPacketListener packetListener,
                            ProtocolVersion<?> protocolVersion)
    {
        this.dioriteCore = dioriteCore;
        this.serverAddress = serverAddress;
        this.packetListener = packetListener;
        this.protocolVersion = protocolVersion;

        this.playerTimeout = dioriteCore.getConfig().getPlayerTimeout();
    }

    protected long lastKeepAlive = System.currentTimeMillis();
    protected long sentAlive     = System.currentTimeMillis();
    protected int ping;

    public DioriteCore getDioriteCore()
    {
        return this.dioriteCore;
    }

    @Nullable
    public SocketAddress getSocketAddress()
    {
        return this.address;
    }

    public void setProtocolVersion(ProtocolVersion<?> protocolVersion)
    {
        this.protocolVersion = protocolVersion;
    }

    public ProtocolVersion<?> getProtocolVersion()
    {
        return this.protocolVersion;
    }

    public boolean isPreparing()
    {
        return this.preparing;
    }

    public void update()
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        assert this.channel != null;
        this.nextPacket();
        this.channel.flush();
    }

    public void sendPackets(Packet<?>[] packets)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if (this.isChannelOpen())
        {
            this.nextPacket();
            for (Packet<?> packet : packets)
            {
                this.sendPacket(packet, null);
            }
        }
        else
        {
            for (Packet<?> packet : packets)
            {
                this.packetQueue.add(new QueuedPacket(packet, null));
            }
        }
    }

    public void sendPacket(Packet<?> packet)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if (this.isChannelOpen())
        {
            this.nextPacket();
            this.sendPacket(packet, null);
        }
        else
        {
            this.packetQueue.add(new QueuedPacket(packet, null));
        }
    }

    @SafeVarargs
    public final void sendPacket(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> listener,
                                 GenericFutureListener<? extends Future<? super Void>>... listeners)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if (this.isChannelOpen())
        {
            this.nextPacket();
            this.sendPacket(packet, ArrayUtils.add(listeners, 0, listener));
        }
        else
        {
            this.packetQueue.add(new QueuedPacket(packet, ArrayUtils.add(listeners, 0, listener)));
        }
    }

    private void sendPacket(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>>[] listeners)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        assert this.channel != null;
        // TODO: sentAlive
//        if ((packet instanceof PacketPlayClientboundKeepAlive) || (packet instanceof PacketPlayServerboundKeepAlive))
//        {
//            this.sentAlive = System.currentTimeMillis();
//        }
        ProtocolState ep1 = packet.state();
        ProtocolState ep2 = this.channel.attr(ServerConnection.protocolKey).get();
        if (ep2 != ep1)
        {
            this.channel.config().setAutoRead(false);
        }
        if (this.channel.eventLoop().inEventLoop())
        {
            if (ep1 != ep2)
            {
                this.setProtocol(ep1);
            }
            ChannelFuture channelfuture = this.channel.writeAndFlush(packet);
            if (listeners != null)
            {
                channelfuture.addListeners(listeners);
            }
            channelfuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        else
        {
            this.channel.eventLoop().execute(() ->
                                             {
                                                 if (ep1 != ep2)
                                                 {
                                                     this.setProtocol(ep1);
                                                 }
                                                 ChannelFuture channelFuture = this.channel.writeAndFlush(packet);
                                                 if (listeners != null)
                                                 {
                                                     channelFuture.addListeners(listeners);
                                                 }
                                                 channelFuture.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                                             });
        }
    }

    private void nextPacket()
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if (this.isChannelOpen())
        {
            while (! this.packetQueue.isEmpty())
            {
                QueuedPacket queuedpacket = this.packetQueue.poll();
                this.sendPacket(queuedpacket.getPacket(), queuedpacket.getListeners());
            }
        }
    }

    public abstract void enableEncryption(SecretKey secretkey);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet<? super ServerboundPacketListener> msg) throws Exception
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        assert this.channel != null;
        if (this.channel.isOpen())
        {
            msg.handle(this.packetListener);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelHandlerContext, Throwable throwable)
    {
        this.dioriteCore.debug(throwable);
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        assert this.channel != null;
        if (this.channel.isOpen())
        {
            this.close(ChatMessage.fromLegacy("Internal Exception: " + throwable)); // TODO change message
            this.channel.close();
            this.handleClosed();
        }
        else
        {
            this.preparing = false;
            this.packetQueue.clear();
            this.disconnectMessage = ChatMessage.fromLegacy("Internal Exception: " + throwable); // TODO change message
            this.handleClosed();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext channelHandlerContext) throws Exception
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        super.channelActive(channelHandlerContext);
        this.channel = channelHandlerContext.channel();
        this.address = this.channel.remoteAddress();

        this.preparing = false;
        try
        {
            this.setProtocol(ProtocolState.HANDSHAKE);
        }
        catch (Throwable throwable)
        {
            throwable.printStackTrace();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext channelHandlerContext)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        this.close(ChatMessage.fromLegacy("disconnect.endOfStream")); // TODO change message
        this.handleClosed();
    }

    public void setProtocol(ProtocolState protocolState)
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        assert this.channel != null;
        this.channel.attr(ServerConnection.protocolKey).set(protocolState);
        this.channel.config().setAutoRead(true);
    }

    public void checkAlive()
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if ((System.currentTimeMillis() - this.lastKeepAlive) > this.playerTimeout)
        {
            this.packetListener.disconnect(ChatMessage.fromLegacy("§4Timeout")); // TODO change message
        }
    }

    public boolean hasNoChannel()
    {
        return this.channel == null;
    }

    public boolean isChannelOpen()
    {
        return (this.channel != null) && this.channel.isOpen();
    }

    public void checkConnection()
    {
        if (this.closed)
        {
            this.handleClosed();
            return;
        }
        if (! this.isChannelOpen())
        {
            if (this.disconnectMessage != null)
            {
                this.packetListener.disconnect(this.disconnectMessage);
            }
            else
            {
                this.packetListener.disconnect(ChatMessage.fromLegacy("Disconnected"));
            }
        }
    }

    public void close(ChatMessage chatMessage, boolean wasSafe)
    {
        if (this.closed)
        {
            return;
        }
        this.preparing = false;
        this.packetQueue.clear();
        if (! wasSafe)
        {
            // TODO inform server about disconnected player
        }
        this.closed = true;
        assert this.channel != null;
        if (this.channel.isOpen())
        {
            this.channel.close();
            this.disconnectMessage = chatMessage;
        }
        this.handleClosed();

    }

    public void close(ChatMessage chatMessage)
    {
        this.close(chatMessage, false);
    }

    public abstract void setCompression(int i);

    public void setPacketListener(ServerboundPacketListener packetListener)
    {
        this.packetListener = packetListener;
    }

    public InetSocketAddress getServerAddress()
    {
        return this.serverAddress;
    }

    public int getPing()
    {
        return this.ping;
    }

    public void setPing(int ping)
    {
        this.ping = ping;
    }

    public void handleClosed()
    {
        this.dioriteCore.getServerConnection().remove(this);
    }
}
