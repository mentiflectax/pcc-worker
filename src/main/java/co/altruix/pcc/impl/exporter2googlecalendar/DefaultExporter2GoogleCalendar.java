/**
 * This file is part of Project Control Center (PCC).
 * 
 * PCC (Project Control Center) project is intellectual property of 
 * Dmitri Anatol'evich Pisarenko.
 * 
 * Copyright 2010, 2011 Dmitri Anatol'evich Pisarenko
 * All rights reserved
 *
 **/

package co.altruix.pcc.impl.exporter2googlecalendar;

import java.io.IOException;
import java.net.URL;
import java.security.PrivateKey;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.silverstrike.pcc.api.model.Booking;
import at.silverstrike.pcc.api.model.UserData;
import at.silverstrike.pcc.api.persistence.Persistence;
import at.silverstrike.pcc.api.privatekeyreader.PrivateKeyReader;
import at.silverstrike.pcc.api.privatekeyreader.PrivateKeyReaderFactory;
import at.silverstrike.pcc.impl.privatekeyreader.DefaultPrivateKeyReaderFactory;

import com.google.gdata.client.authn.oauth.GoogleOAuthParameters;
import com.google.gdata.client.authn.oauth.OAuthException;
import com.google.gdata.client.authn.oauth.OAuthRsaSha1Signer;
import com.google.gdata.client.calendar.CalendarService;
import com.google.gdata.data.DateTime;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.calendar.CalendarEntry;
import com.google.gdata.data.calendar.CalendarEventEntry;
import com.google.gdata.data.calendar.CalendarEventFeed;
import com.google.gdata.data.calendar.CalendarFeed;
import com.google.gdata.data.extensions.When;
import com.google.gdata.util.ServiceException;
import com.google.inject.Injector;

import ru.altruix.commons.api.di.PccException;
import co.altruix.pcc.api.exporter2googlecalendar.Exporter2GoogleCalendar;

/**
 * @author DP118M
 * 
 */
class DefaultExporter2GoogleCalendar implements Exporter2GoogleCalendar {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(DefaultExporter2GoogleCalendar.class);

    private Injector injector;

    private String consumerKey;

    private String calendarScope;

    private UserData user;

    private String allCalendarsFeedUrl;

    @Override
    public void run() throws PccException {
        final Persistence persistence = this.injector.getInstance(Persistence.class);
        
        try {
            final PrivateKey privKey = getPrivateKey();
            final OAuthRsaSha1Signer signer = new OAuthRsaSha1Signer(privKey);

            final GoogleOAuthParameters oauthParameters =
                    new GoogleOAuthParameters();
            oauthParameters.setOAuthConsumerKey(consumerKey);
            oauthParameters.setScope(calendarScope);

            oauthParameters.setOAuthVerifier(user
                    .getGoogleCalendarOAuthVerifier()); // Verifier from the
                                                        // interactive part
            oauthParameters.setOAuthToken(user.getGoogleCalendarOAuthToken()); // Access
                                                                                // token
                                                                                // from
                                                                                // the
                                                                                // interactive
                                                                                // part
            oauthParameters.setOAuthTokenSecret(user
                    .getGoogleCalendarOAuthTokenSecret()); // Token secret from
                                                           // the interactive
                                                           // part

            final CalendarService calendarService =
                    new CalendarService(consumerKey);

            calendarService
                    .setOAuthCredentials(oauthParameters, signer);

            final URL feedUrl =
                    new URL(
                            allCalendarsFeedUrl);
            final CalendarFeed resultFeed =
                    calendarService.getFeed(feedUrl, CalendarFeed.class);

            LOGGER.debug("resultFeed: {}", resultFeed);

            LOGGER.debug("Your calendars:");

            CalendarEntry pccCalendar = null;
            for (int i = 0; (i < resultFeed.getEntries().size())
                    && (pccCalendar == null); i++) {
                final CalendarEntry entry = resultFeed.getEntries().get(i);

                if ("PCC".equals(entry.getTitle().getPlainText())) {
                    pccCalendar = entry;
                }
            }

            // Delete all events in the PCC calendar

            LOGGER.debug(
                    "PCC calendar: edit link='{}', self link='{}', content='{}', id='{}'",
                    new Object[] { pccCalendar.getEditLink().getHref(),
                            pccCalendar.getSelfLink().getHref(),
                            pccCalendar.getContent(), pccCalendar.getId() });

            final String calendarId =
                    pccCalendar
                            .getId()
                            .substring(
                                    "http://www.google.com/calendar/feeds/default/calendars/"
                                            .length());
            final URL pccCalendarUrl =
                    new URL(
                            "https://www.google.com/calendar/feeds/${calendarId}/private/full"
                                    .replace("${calendarId}", calendarId));

            LOGGER.debug("pccCalendarUrl: {}", pccCalendarUrl);
            // calendarService.getFeed(feedUrl, feedClass)

            final CalendarEventFeed pccEventFeed =
                    calendarService.getFeed(pccCalendarUrl,
                            CalendarEventFeed.class);
            for (final CalendarEventEntry curEvent : pccEventFeed.getEntries()) {
                LOGGER.debug("Deleting event ''", curEvent);
                curEvent.delete();
            }

            final List<Booking> bookings =
                    persistence.getBookings(user);

            LOGGER.debug("Bookings to export: {}", bookings.size());

            for (final Booking curBooking : bookings) {
                LOGGER.debug(
                        "Exporting: start date time: {}, end date time: {}",
                        new Object[] { curBooking.getStartDateTime(),
                                curBooking.getEndDateTime() });

                final CalendarEventEntry event = new CalendarEventEntry();

                event.setTitle(new PlainTextConstruct(curBooking.getProcess()
                        .getName()));

                final When eventTime = new When();
                final DateTime startDateTime =
                        new DateTime(curBooking.getStartDateTime().getTime());
                final DateTime endDateTime =
                        new DateTime(curBooking.getEndDateTime().getTime());

                eventTime.setStartTime(startDateTime);
                eventTime.setEndTime(endDateTime);

                event.addTime(eventTime);

                calendarService.insert(pccCalendarUrl, event);
            }
        } catch (final IOException exception) {
            LOGGER.error("", exception);
        } catch (final OAuthException exception) {
            LOGGER.error("", exception);
        } catch (final ServiceException exception) {
            LOGGER.error("", exception);
        }

    }

    private PrivateKey getPrivateKey() {
        final PrivateKeyReaderFactory factory =
                new DefaultPrivateKeyReaderFactory();
        final PrivateKeyReader reader = factory.create();

        reader.setInputStream(getClass().getClassLoader()
                        .getResourceAsStream("privatekey"));

        try {
            reader.run();

            return reader.getPrivateKey();
        } catch (final PccException exception) {
            LOGGER.error("", exception);
            return null;
        }
    }

    public void setInjector(final Injector aInjector) {
        this.injector = aInjector;
    }

    @Override
    public void setConsumerKey(final String aConsumerKey) {
        this.consumerKey = aConsumerKey;
    }

    @Override
    public void setCalendarScope(final String aCalendarScope) {
        this.calendarScope = aCalendarScope;
    }

    @Override
    public void setUser(final UserData aUser) {
        this.user = aUser;
    }

    @Override
    public void setAllCalendarsFeedUrl(final String aAllCalendarsFeedUrl) {
        this.allCalendarsFeedUrl = aAllCalendarsFeedUrl;
    }

}