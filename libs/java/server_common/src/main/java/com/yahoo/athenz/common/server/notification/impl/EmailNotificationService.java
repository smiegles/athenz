/*
 * Copyright 2019 Oath Holdings Inc.
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
 * limitations under the License.
 */

package com.yahoo.athenz.common.server.notification.impl;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.*;
import com.yahoo.athenz.common.server.notification.Notification;
import com.yahoo.athenz.common.server.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/*
 * This is a reference implementation using AWS SES.
 */
public class EmailNotificationService implements NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailNotificationService.class);

    private static final String AT = "@";
    private static final String CHARSET_UTF_8 = "UTF-8";
    private static final String USER_DOMAIN_DEFAULT = "user";
    private static final String PROP_USER_DOMAIN = "athenz.user_domain";
    private static final String PROP_NOTIFICATION_EMAIL_DOMAIN_FROM = "athenz.notification_email_domain_from";
    private static final String PROP_NOTIFICATION_EMAIL_DOMAIN_TO = "athenz.notification_email_domain_to";
    private static final String PROP_NOTIFICATION_WORKFLOW_URL = "athenz.notification_workflow_url";
    private static final String PROP_NOTIFICATION_ATHENZ_UI_URL = "athenz.notification_athenz_ui_url";
    private static final String PROP_NOTIFICATION_EMAIL_FROM = "athenz.notification_email_from";

    private static final String MEMBERSHIP_APPROVAL_SUBJECT = "athenz.notification.email.membership.approval.subject";
    private static final String MEMBERSHIP_APPROVAL_REMINDER_SUBJECT = "athenz.notification.email.membership.reminder.subject";
    private static final String MEMBERSHIP_APPROVAL_BODY = "athenz.notification.email.membership.approval.body";
    private static final String MEMBERSHIP_APPROVAL_REMINDER_BODY = "athenz.notification.email.membership.reminder.body";

    private static final String DOMAIN_MEMBER_EXPIRY_SUBJECT = "athenz.notification.email.domain.member.expiry.subject";
    private static final String DOMAIN_MEMBER_EXPIRY_BODY_HEADER = "athenz.notification.email.domain.member.expiry.body.header";
    private static final String DOMAIN_MEMBER_EXPIRY_BODY_ENTRY = "athenz.notification.email.domain.member.expiry.body.entry";
    private static final String DOMAIN_MEMBER_EXPIRY_BODY_FOOTER = "athenz.notification.email.domain.member.expiry.body.footer";

    private static final String PRINCIPAL_EXPIRY_SUBJECT = "athenz.notification.email.principal.expiry.subject";
    private static final String PRINCIPAL_EXPIRY_BODY_HEADER = "athenz.notification.email.principal.expiry.body.header";
    private static final String PRINCIPAL_EXPIRY_BODY_ENTRY = "athenz.notification.email.principal.expiry.body.entry";
    private static final String PRINCIPAL_EXPIRY_BODY_FOOTER = "athenz.notification.email.principal.expiry.body.footer";

    private static final String MEMBERSHIP_APPROVAL_FOOTER = "athenz.notification.email.membership.footer";

    private static final int SES_RECIPIENTS_LIMIT_PER_MESSAGE = 50;

    // can be moved to constructor which can take Locale as input parameter and return appropriate resource bundle
    private static final ResourceBundle RB = ResourceBundle.getBundle("messages/ServerCommon");

    private final AmazonSimpleEmailService ses;

    private String userDomainPrefix;
    private String emailDomainFrom;
    private String emailDomainTo;
    private String workflowUrl;
    private String athenzUIUrl;
    private String from;

    EmailNotificationService() {
        this(initSES());
    }

    EmailNotificationService(AmazonSimpleEmailService ses) {
        this.ses = ses;
        String userDomain = System.getProperty(PROP_USER_DOMAIN, USER_DOMAIN_DEFAULT);
        userDomainPrefix = userDomain + "\\.";
        emailDomainFrom = System.getProperty(PROP_NOTIFICATION_EMAIL_DOMAIN_FROM);
        emailDomainTo = System.getProperty(PROP_NOTIFICATION_EMAIL_DOMAIN_TO);
        workflowUrl = System.getProperty(PROP_NOTIFICATION_WORKFLOW_URL);
        athenzUIUrl = System.getProperty(PROP_NOTIFICATION_ATHENZ_UI_URL);
        from = System.getProperty(PROP_NOTIFICATION_EMAIL_FROM);
    }

    private static AmazonSimpleEmailService initSES() {
        ///CLOVER:OFF
        Region region = Regions.getCurrentRegion();
        if (region == null) {
            region = Region.getRegion(Regions.US_EAST_1);
        }
        return AmazonSimpleEmailServiceClientBuilder.standard().withRegion(region.getName()).build();
        ///CLOVER:ON
    }

    @Override
    public boolean notify(Notification notification) {
        if (notification == null) {
            return false;
        }

        final String subject = getSubject(notification.getType());
        final String body = getBody(notification.getType(), notification.getDetails());
        Set<String> recipients = getFullyQualifiedEmailAddresses(notification.getRecipients());
        return sendEmail(recipients, subject, body);
    }

    String getBody(String type, Map<String, String> details) {

        String body = "";
        switch (type) {
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL:
                body = getMembershipApprovalBody(details);
                break;
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL_REMINDER:
                body = getMembershipApprovalReminderBody();
                break;
            case NOTIFICATION_TYPE_DOMAIN_MEMBER_EXPIRY_REMINDER:
                body = getDomainMemberExpiryBody(details);
                break;
            case NOTIFICATION_TYPE_PRINCIPAL_EXPIRY_REMINDER:
                body = getPrincipalExpiryBody(details);
                break;
        }
        body = body + getFooter();
        return body;
    }

    String getFooter() {
        return RB.getString(MEMBERSHIP_APPROVAL_FOOTER);
    }

    String getSubject(String type) {
        String subject = "";
        switch (type) {
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL:
                subject = RB.getString(MEMBERSHIP_APPROVAL_SUBJECT);
                break;
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL_REMINDER:
                subject = RB.getString(MEMBERSHIP_APPROVAL_REMINDER_SUBJECT);
                break;
            case NOTIFICATION_TYPE_DOMAIN_MEMBER_EXPIRY_REMINDER:
                subject = RB.getString(DOMAIN_MEMBER_EXPIRY_SUBJECT);
                break;
            case NOTIFICATION_TYPE_PRINCIPAL_EXPIRY_REMINDER:
                subject = RB.getString(PRINCIPAL_EXPIRY_SUBJECT);
                break;
        }
        return subject;
    }

    Set<String> getFullyQualifiedEmailAddresses(Set<String> recipients) {
        return recipients.stream()
                .map(s -> s.replaceAll(userDomainPrefix, ""))
                .map(r -> r + AT + emailDomainTo)
                .collect(Collectors.toSet());
    }

    String getMembershipApprovalReminderBody() {
        return MessageFormat.format(RB.getString(MEMBERSHIP_APPROVAL_REMINDER_BODY), workflowUrl);
    }

    String getMembershipApprovalBody(Map<String, String> metaDetails) {
        return MessageFormat.format(RB.getString(MEMBERSHIP_APPROVAL_BODY), metaDetails.get(NotificationService.NOTIFICATION_DETAILS_DOMAIN),
                metaDetails.get(NotificationService.NOTIFICATION_DETAILS_ROLE), metaDetails.get(NotificationService.NOTIFICATION_DETAILS_MEMBER),
                metaDetails.get(NotificationService.NOTIFICATION_DETAILS_REASON), metaDetails.get(NotificationService.NOTIFICATION_DETAILS_REQUESTOR),
                workflowUrl);
    }

    String getDomainMemberExpiryBody(Map<String, String> metaDetails) {

        // first generate the header

        StringBuilder body = new StringBuilder(256);
        final String domainName = metaDetails.get(NotificationService.NOTIFICATION_DETAILS_DOMAIN);
        body.append(MessageFormat.format(RB.getString(DOMAIN_MEMBER_EXPIRY_BODY_HEADER), domainName));

        // now parse and generate the member expiry entries

        final String roleNames = metaDetails.get(NOTIFICATION_DETAILS_EXPIRY_MEMBERS);
        processExpiryEntry(body, roleNames, RB.getString(DOMAIN_MEMBER_EXPIRY_BODY_ENTRY));

        // and finally the footer

        body.append(MessageFormat.format(RB.getString(DOMAIN_MEMBER_EXPIRY_BODY_FOOTER),
                athenzUIUrl, domainName));
        return body.toString();
    }

    String getPrincipalExpiryBody(Map<String, String> metaDetails) {

        // first generate the header

        StringBuilder body = new StringBuilder(256);
        body.append(MessageFormat.format(RB.getString(PRINCIPAL_EXPIRY_BODY_HEADER),
                metaDetails.get(NotificationService.NOTIFICATION_DETAILS_MEMBER)));

        // now parse and generate the member expiry entries

        final String roleNames = metaDetails.get(NOTIFICATION_DETAILS_EXPIRY_ROLES);
        processExpiryEntry(body, roleNames, RB.getString(PRINCIPAL_EXPIRY_BODY_ENTRY));

        // and finally the footer

        body.append(MessageFormat.format(RB.getString(PRINCIPAL_EXPIRY_BODY_FOOTER), athenzUIUrl));
        return body.toString();
    }

    void processExpiryEntry(StringBuilder body, final String entryNames, final String entryFormat) {
        // if we have no entry names then there is nothing to process
        if (entryNames == null) {
            return;
        }
        String[] entries = entryNames.split("\\|");
        for (String entry : entries) {
            String[] comps = entry.split(";");
            if (comps.length != 3) {
                continue;
            }
            body.append(MessageFormat.format(entryFormat, comps[0], comps[1], comps[2]));
            body.append('\n');
        }
    }

    boolean sendEmail(Set<String> recipients, String subject, String body) {
        final AtomicInteger counter = new AtomicInteger();
        boolean status = true;
        // SES imposes a limit of 50 recipients. So we convert the recipients into batches
        if (recipients.size() > SES_RECIPIENTS_LIMIT_PER_MESSAGE) {
            final Collection<List<String>> recipientsBatch = recipients.stream()
                    .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / SES_RECIPIENTS_LIMIT_PER_MESSAGE))
                    .values();
            for (List<String> recipientsSegment : recipientsBatch) {
                status = sendEmail(subject, body, status, recipientsSegment);
            }
        } else {
            status = sendEmail(subject, body, status, new ArrayList<>(recipients));
        }

        return status;
    }

    private boolean sendEmail(String subject, String body, boolean status, Collection<String> recipients) {
            try {
                SendEmailRequest request = new SendEmailRequest()
                        .withDestination(new Destination()
                                .withBccAddresses(recipients))
                        .withMessage(new Message()
                                .withBody(new Body()
                                        .withHtml(new Content()
                                                .withCharset(CHARSET_UTF_8).withData(body)))
                                .withSubject(new Content()
                                        .withCharset(CHARSET_UTF_8).withData(subject)))
                        .withSource(from + AT + emailDomainFrom);
                SendEmailResult result = ses.sendEmail(request);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Email with messageId={} sent successfully.", result.getMessageId());
                }
                status = status && result != null;
            } catch (Exception ex) {
                LOGGER.error("The email could not be sent. Error message: {}", ex.getMessage());
                status = false;
            }

        return status;
    }
}
