//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;
import org.eclipse.jetty.websocket.common.message.MessageReader;
import org.eclipse.jetty.websocket.common.message.NullMessage;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;
import org.eclipse.jetty.websocket.common.util.TextUtil;

/**
 * Handler for Annotated User WebSocket objects.
 */
public class JettyAnnotatedEventDriver extends AbstractEventDriver
{
    private static final Logger LOG = Log.getLogger(JettyAnnotatedEventDriver.class);
    private final JettyAnnotatedMetadata events;
    private boolean hasCloseBeenCalled = false;
    private BatchMode batchMode;

    public JettyAnnotatedEventDriver(WebSocketPolicy policy, Object websocket, JettyAnnotatedMetadata events)
    {
        super(policy, websocket);
        this.events = Objects.requireNonNull(events, "JettyAnnotatedMetadata may not be null");

        WebSocket anno = websocket.getClass().getAnnotation(WebSocket.class);
        // Setup the policy
        if (anno.maxTextMessageSize() > 0)
        {
            this.policy.setMaxTextMessageSize(anno.maxTextMessageSize());
        }
        if (anno.maxBinaryMessageSize() > 0)
        {
            this.policy.setMaxBinaryMessageSize(anno.maxBinaryMessageSize());
        }
        if (anno.inputBufferSize() > 0)
        {
            this.policy.setInputBufferSize(anno.inputBufferSize());
        }
        if (anno.maxIdleTime() > 0)
        {
            this.policy.setIdleTimeout(anno.maxIdleTime());
        }
        this.batchMode = anno.batchMode();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("ctor / object={}, policy={}, batchMode={}, events={}", websocket, policy, batchMode, events);
        }
    }

    @Override
    public BatchMode getBatchMode()
    {
        return this.batchMode;
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryFrame({}, {}) - events.onBinary={}", BufferUtil.toDetailString(buffer), fin, events.onBinary);
        }

        if (events.onBinary == null)
        {
            // not interested in binary events
            if (activeMessage == null)
            {
                activeMessage = NullMessage.INSTANCE;
            }

            return;
        }

        if (activeMessage == null)
        {
            if (events.onBinary.isStreaming())
            {
                final MessageInputStream inputStream = new MessageInputStream(session);
                activeMessage = inputStream;
                dispatch(() ->
                {
                    try
                    {
                        events.onBinary.call(websocket, session, inputStream);
                    }
                    catch (Throwable t)
                    {
                        // dispatched calls need to be reported
                        session.close(t);
                    }

                    inputStream.close();
                });
            }
            else
            {
                activeMessage = new SimpleBinaryMessage(this);
            }
        }

        appendMessage(buffer, fin);
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onBinaryMessage([{}]) - events.onBinary={}", data.length, events.onBinary);
        }

        if (events.onBinary != null)
        {
            events.onBinary.call(websocket, session, data, 0, data.length);
        }
    }

    @Override
    public void onClose(CloseInfo close)
    {
        if (hasCloseBeenCalled)
        {
            // avoid duplicate close events (possible when using harsh Session.disconnect())
            return;
        }
        hasCloseBeenCalled = true;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("onClose({}) - events.onClose={}", close, events.onClose);
        }

        if (events.onClose != null)
        {
            events.onClose.call(websocket, session, close.getStatusCode(), close.getReason());
        }
    }

    @Override
    public void onConnect()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onConnect() - events.onConnect={}", events.onConnect);
        }

        if (events.onConnect != null)
        {
            events.onConnect.call(websocket, session);
        }
    }

    @Override
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onError({}) - events.onError={}", cause.getClass().getName(), events.onError);
        }

        if (events.onError != null)
        {
            events.onError.call(websocket, session, cause);
        }
        else
        {
            LOG.warn("Unable to report throwable to websocket (no @OnWebSocketError handler declared): " + websocket.getClass().getName(), cause);
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onFrame({}) - events.onFrame={}", frame, events.onFrame);
        }

        if (events.onFrame != null)
        {
            events.onFrame.call(websocket, session, frame);
        }
    }

    @Override
    public void onInputStream(InputStream stream)
    {
        Objects.requireNonNull(stream, "InputStream may not be null");

        if (LOG.isDebugEnabled())
        {
            LOG.debug("onInputStream({}) - events.onBinary={}", stream.getClass().getName(), events.onBinary);
        }

        if (events.onBinary != null)
        {
            events.onBinary.call(websocket, session, stream);
        }
    }

    @Override
    public void onReader(Reader reader)
    {
        Objects.requireNonNull(reader, "Reader may not be null");

        if (LOG.isDebugEnabled())
        {
            LOG.debug("onReader({}) - events.onText={}", reader.getClass().getName(), events.onText);
        }

        if (events.onText != null)
        {
            events.onText.call(websocket, session, reader);
        }
    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onTextFrame({}, {}) - events.onText={}", BufferUtil.toDetailString(buffer), fin, events.onText);
        }

        if (events.onText == null)
        {
            // not interested in text events
            if (activeMessage == null)
            {
                activeMessage = NullMessage.INSTANCE;
            }
            return;
        }

        if (activeMessage == null)
        {
            if (events.onText.isStreaming())
            {
                MessageInputStream inputStream = new MessageInputStream(session);
                activeMessage = new MessageReader(inputStream);
                final MessageAppender msg = activeMessage;
                dispatch(() ->
                {
                    try
                    {
                        events.onText.call(websocket, session, msg);
                    }
                    catch (Throwable t)
                    {
                        // dispatched calls need to be reported
                        session.close(t);
                    }

                    inputStream.close();
                });
            }
            else
            {
                activeMessage = new SimpleTextMessage(this);
            }
        }

        appendMessage(buffer, fin);
    }

    @Override
    public void onTextMessage(String message)
    {
        if (LOG.isDebugEnabled())
        {
            if (message == null)
                LOG.debug("onTextMessage(<null>) - events.onText={}", events.onText);
            else
                LOG.debug("onTextMessage([{}] \"{}\") - events.onText={}", message.length(), TextUtil.maxStringLength(60, message), events.onText);
        }

        if (events.onText != null)
        {
            events.onText.call(websocket, session, message);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), websocket);
    }
}
