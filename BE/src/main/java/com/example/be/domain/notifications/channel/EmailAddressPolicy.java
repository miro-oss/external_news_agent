package com.example.be.domain.notifications.channel;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** One configured destination must remain exactly one SMTP recipient. */
public final class EmailAddressPolicy {

    private EmailAddressPolicy() {}

    public static InternetAddress mailbox(String value) throws AddressException {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new AddressException("Invalid mailbox");
        }
        InternetAddress[] addresses = InternetAddress.parse(value.trim(), true);
        if (addresses.length != 1 || addresses[0].isGroup()) {
            throw new AddressException("Expected one mailbox");
        }
        InternetAddress address = addresses[0];
        address.validate();
        if (!address.getAddress().contains("@")) {
            throw new AddressException("Mailbox requires a domain");
        }
        return address;
    }
}
