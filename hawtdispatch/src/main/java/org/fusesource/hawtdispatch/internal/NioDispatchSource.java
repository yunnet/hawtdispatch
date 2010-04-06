/**
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 */
package org.fusesource.hawtdispatch.internal;

import org.fusesource.hawtdispatch.DispatchQueue;
import org.fusesource.hawtdispatch.DispatchSource;
import org.fusesource.hawtdispatch.internal.Dispatcher;
import org.fusesource.hawtdispatch.internal.BaseSuspendable;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static org.fusesource.hawtdispatch.DispatchQueue.QueueType.THREAD_QUEUE;

/**
 * SelectableDispatchContext
 * <p>
 * Description:
 * </p>
 * 
 * @author cmacnaug
 * @version 1.0
 */
final public class NioDispatchSource extends BaseSuspendable implements DispatchSource {

    public static final boolean DEBUG = false;

    private final SelectableChannel channel;
    private volatile DispatchQueue selectorQueue;

    final AtomicBoolean canceled = new AtomicBoolean();
    final int interestOps;

    private volatile DispatchQueue targetQueue;
    private Runnable cancelHandler;
    private Runnable eventHandler;

    // These fields are only accessed by the ioManager's thread.
    class KeyState {
        int readyOps;
        SelectionKey key;
        NioAttachment attachment;
    }

    final private ThreadLocal<KeyState> keyState = new ThreadLocal<KeyState>();

    public NioDispatchSource(Dispatcher dispatcher, SelectableChannel channel, int interestOps, DispatchQueue targetQueue) {
        if( interestOps == 0 ) {
            throw new IllegalArgumentException("invalid interest ops");
        }
        this.channel = channel;
        this.selectorQueue = pickThreadQueue(dispatcher, targetQueue);
        this.targetQueue = targetQueue;
        this.interestOps = interestOps;
        this.suspended.incrementAndGet();
    }


    static private DispatchQueue pickThreadQueue(Dispatcher dispatcher, DispatchQueue targetQueue) {
        // Try to select a thread queue associated /w the target if available..
        DispatchQueue selectorQueue = targetQueue;
        while( selectorQueue.getQueueType()!=THREAD_QUEUE  && selectorQueue.getTargetQueue() !=null ) {
            selectorQueue = selectorQueue.getTargetQueue();
        }
        // otherwise.. just use a random thread queue..
        if( selectorQueue.getQueueType()!=THREAD_QUEUE ) {
            selectorQueue = dispatcher.getRandomThreadQueue();
        }

        return selectorQueue;
    }

    @Override
    protected void onStartup() {
        if( eventHandler==null ) {
            throw new IllegalArgumentException("eventHandler must be set");
        }
        register_on(selectorQueue);
    }


    public void cancel() {
        if( canceled.compareAndSet(false, true) && selectorQueue!=null ) {
            selectorQueue.dispatchAsync(new Runnable(){
                public void run() {
                    internal_cancel();
                }
            });
        }
    }

    void internal_cancel() {
        key_cancel();
        targetQueue.release();
        if( cancelHandler!=null ) {
            cancelHandler.run();
        }
    }

    private void key_cancel() {
        // Deregister...
        KeyState state = keyState.get();
        if( state==null ) {
            return;
        }
        debug("canceling source");
        state.attachment.sources.remove(this);

        if( state.attachment.sources.isEmpty() ) {
            debug("canceling key.");
            // This will make sure that the key is removed
            // from the ioManager.
            state.key.cancel();

            // Running a select to remove the canceled key.
            Selector selector =  WorkerThread.currentWorkerThread().getNioManager().getSelector();
            try {
                selector.selectNow();
            } catch (IOException e) {
                debug(e, "Error canceling");
            }
        }
        debug("Canceled selector on "+WorkerThread.currentWorkerThread().getDispatchQueue().getLabel() );
        keyState.remove();
    }

    private void cancel_on(final DispatchQueue queue) {
        queue.dispatchAsync(new Runnable(){
            public void run() {
                key_cancel();
            }
        });
    }

    private void register_on(final DispatchQueue queue) {
        queue.dispatchAsync(new Runnable(){
            public void run() {
                assert keyState.get()==null;

                debug("Registering on selector "+ WorkerThread.currentWorkerThread().getDispatchQueue().getLabel() );
                Selector selector = WorkerThread.currentWorkerThread().getNioManager().getSelector();
                try {
                    KeyState state = new KeyState();
                    state.key = channel.keyFor(selector);
                    if( state.key==null ) {
                        state.key = channel.register(selector, interestOps);
                        state.attachment = new NioAttachment();
                        state.key.attach(state.attachment);
                    } else {
                        state.attachment = (NioAttachment)state.key.attachment();
                    }
                    state.key.interestOps(state.key.interestOps()|interestOps);
                    state.attachment.sources.add(NioDispatchSource.this);
                    keyState.set(state);
                } catch (ClosedChannelException e) {
                    debug(e, "could not register with selector");
                }
                debug("Registered");
            }
        });
    }


    public void fire(int readyOps) {
        final KeyState state = keyState.get();
        if( state==null ) {
            return;
        }
        state.readyOps |= readyOps;
        if( state.readyOps!=0 && !isSuspended() && !isCanceled() ) {
            state.readyOps = 0;
            targetQueue.dispatchAsync(new Runnable() {
                public void run() {
                    if( !isSuspended() && !isCanceled()) {
                        debug("fired %d %s", interestOps, state.toString());
                        eventHandler.run();
                        updateInterest();
                    }
                }
            });
        }
    }


    private void updateInterest() {
        if( isCurrent(selectorQueue) ) {
            if( !isSuspended() && !isCanceled() ) {
                debug("adding interest: %d", interestOps);
                KeyState state = keyState.get();
                if( state==null ) {
                    return;
                }

                if( state.key.isValid() ) {
                    state.key.interestOps(state.key.interestOps()|interestOps);
                }
            }
        } else {
            selectorQueue.dispatchAsync(new Runnable(){
                public void run() {
                    if( !isSuspended() && !isCanceled() ) {
                        debug("adding interest: %d", interestOps);
                        KeyState state = keyState.get();
                        if( state==null ) {
                            return;
                        }

                        if( state.key.isValid() ) {
                            state.key.interestOps(state.key.interestOps()|interestOps);
                        }
                    }
                }
            });
        }
    }

    private boolean isCurrent(DispatchQueue q) {
        WorkerThread thread = WorkerThread.currentWorkerThread();
        if( thread == null )
            return false;
        return thread.getDispatchQueue() == q;
    }

    @Override
    protected void onSuspend() {
        debug("onSuspend");
        super.onSuspend();
    }

    @Override
    protected void onResume() {
        debug("onResume");
        if( isCurrent(selectorQueue) ) {
            KeyState state = keyState.get();
            if( state==null || state.readyOps==0 ) {
                updateInterest();
            } else {
                fire(state.readyOps);
            }
        } else {
            selectorQueue.dispatchAsync(new Runnable(){
                public void run() {
                    KeyState state = keyState.get();
                    if( state==null || state.readyOps==0 ) {
                        updateInterest();
                    } else {
                        fire(interestOps);
                    }
                }
            });
        }
    }

    @Override
    protected void onShutdown() {
        cancel();
        selectorQueue.dispatchAsync(new Runnable(){
            public void run() {
                NioDispatchSource.super.onShutdown();
            }
        });
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    public void setCancelHandler(Runnable cancelHandler) {
        this.cancelHandler = cancelHandler;
    }

    public void setEventHandler(Runnable eventHandler) {
        this.eventHandler = eventHandler;
    }

    public Void getData() {
        return null;
    }

    public void setTargetQueue(DispatchQueue next) {
        if( next!=targetQueue ) {
            // Don't see why someone would concurrently try to set the target..
            // IF we wanted to protect against that we would need to use cas operations here..

            next.retain();
            DispatchQueue previous = this.targetQueue;
            this.targetQueue = next;
            if( previous !=null ) {
                previous.release();
            }
        }

        // The target thread queue might be different. Optimize by switching the selector to it.
        // Do we need to switch selector threads?
        DispatchQueue queue = next;
        while( queue.getQueueType()!=THREAD_QUEUE  && queue.getTargetQueue() !=null ) {
            queue = queue.getTargetQueue();
        }
        if( queue.getQueueType()==THREAD_QUEUE && queue!=selectorQueue ) {
            DispatchQueue previous = selectorQueue;
            debug("Switching to "+queue.getLabel());
            register_on(queue);
            selectorQueue = queue;
            cancel_on(previous);
        }
    }


    public DispatchQueue getTargetQueue() {
        return this.targetQueue;
    }

    protected void debug(String str, Object... args) {
        if (DEBUG) {
            System.out.println(format("[DEBUG] NioDispatchSource %0#10x: ", System.identityHashCode(this))+format(str, args));
        }
    }

    protected void debug(Throwable thrown, String str, Object... args) {
        if (DEBUG) {
            if (str != null) {
                debug(str, args);
            }
            if (thrown != null) {
                thrown.printStackTrace();
            }
        }
    }

}