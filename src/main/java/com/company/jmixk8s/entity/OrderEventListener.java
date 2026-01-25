package com.company.jmixk8s.entity;

import io.jmix.core.DataManager;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.notifications.NotificationManager;
import io.jmix.notifications.channel.impl.InAppNotificationChannel;
import io.jmix.notifications.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    @Autowired
    private DataManager dataManager;
    @Autowired
    protected NotificationManager notificationManager;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderChangedAfterCommit(final EntityChangedEvent<Order> event) {
        try {
            if (event.getType() == EntityChangedEvent.Type.CREATED) {
                Order order = dataManager.load(event.getEntityId()).one();

                notificationManager.createNotification()
                        .withSubject("New order")
                        .withRecipientUsernames("admin")
                        .toChannelsByNames(InAppNotificationChannel.NAME)
                        .withContentType(ContentType.PLAIN)
                        .withBody("A new order with number " + order.getOrderName() + " is created.")
                        .send();
            }
        } catch (Exception e) {
            log.error("Error processing order", e);
        }
    }
}
