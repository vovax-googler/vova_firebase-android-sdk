// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.crashlytics.internal.persistence;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.annotation.Nullable;
import com.google.firebase.crashlytics.internal.CrashlyticsTestCase;
import com.google.firebase.crashlytics.internal.common.CrashlyticsAppQualitySessionsSubscriber;
import com.google.firebase.crashlytics.internal.common.CrashlyticsReportWithSessionId;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Application;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Event;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Event.Application.Execution;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Event.Application.Execution.Signal;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Event.Application.Execution.Thread.Frame;
import com.google.firebase.crashlytics.internal.model.CrashlyticsReport.Session.Event.Application.ProcessDetails;
import com.google.firebase.crashlytics.internal.settings.Settings;
import com.google.firebase.crashlytics.internal.settings.Settings.FeatureFlagData;
import com.google.firebase.crashlytics.internal.settings.SettingsProvider;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import org.junit.Before;
import org.junit.Test;
import org.mockito.internal.util.collections.Sets;

public class CrashlyticsReportPersistenceTest extends CrashlyticsTestCase {
  private static final int VERY_LARGE_UPPER_LIMIT = 9999;
  private static final String APP_QUALITY_SESSION_ID = "9";

  private CrashlyticsReportPersistence reportPersistence;
  private FileStore fileStore;

  private static SettingsProvider createSettingsProviderMock(
      int maxCompleteSessionsCount, int maxCustomExceptionEvents) {

    SettingsProvider settingsProvider = mock(SettingsProvider.class);

    Settings.SessionData sessionData =
        new Settings.SessionData(maxCustomExceptionEvents, maxCompleteSessionsCount);
    Settings settings =
        new Settings(0, sessionData, new FeatureFlagData(true, false, false), 3, 0, 1.0, 1.0, 1);

    when(settingsProvider.getSettingsSync()).thenReturn(settings);
    return settingsProvider;
  }

  private static CrashlyticsAppQualitySessionsSubscriber createSessionsSubscriberMock(
      String appQualitySessionId) {
    CrashlyticsAppQualitySessionsSubscriber mockSessionsSubscriber =
        mock(CrashlyticsAppQualitySessionsSubscriber.class);
    when(mockSessionsSubscriber.getAppQualitySessionId(anyString()))
        .thenReturn(appQualitySessionId);
    return mockSessionsSubscriber;
  }

  @Before
  public void setUp() throws Exception {
    fileStore = new FileStore(getContext());
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(VERY_LARGE_UPPER_LIMIT, VERY_LARGE_UPPER_LIMIT),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));
  }

  @Test
  public void testListSortedOpenSessionIds() {
    final String[] expectedIds = new String[] {"sessionId3", "sessionId2", "sessionId1"};

    reportPersistence.persistReport(makeTestReport(expectedIds[1]));
    reportPersistence.persistReport(makeTestReport(expectedIds[0]));
    reportPersistence.persistReport(makeTestReport(expectedIds[2]));

    final SortedSet<String> openSessionIds = reportPersistence.getOpenSessionIds();

    assertArrayEquals(expectedIds, openSessionIds.toArray());
  }

  @Test
  public void testListSortedOpenSessionIds_noOpenSessions() {
    SortedSet<String> openSessionIds = reportPersistence.getOpenSessionIds();
    assertTrue(openSessionIds.isEmpty());
  }

  @Test
  public void testPersistReports_getStartTimestampMillis() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);

    reportPersistence.persistReport(testReport);
    assertEquals(
        testReport.getSession().getStartedAt() * 1000,
        reportPersistence.getStartTimestampMillis(sessionId));
  }

  @Test
  public void testHasFinalizedReports() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    assertTrue(reportPersistence.hasFinalizedReports());
  }

  @Test
  public void testHasFinalizedReports_noReports() {
    assertFalse(reportPersistence.hasFinalizedReports());
  }

  @Test
  public void testLoadFinalizeReports_noReports_returnsNothing() {
    assertTrue(reportPersistence.loadFinalizedReports().isEmpty());
  }

  @Test
  public void testLoadFinalizedReports_reportWithNoEvents_returnsNothing() {
    final String sessionId = "testSession";
    final long timestamp = System.currentTimeMillis();
    reportPersistence.persistReport(makeTestReport(sessionId));
    reportPersistence.finalizeReports(sessionId, timestamp);
    assertTrue(reportPersistence.loadFinalizedReports().isEmpty());
  }

  @Test
  public void testLoadFinalizedReports_reportThenEvent_returnsReportWithEvent() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(Collections.singletonList(testEvent)),
        finalizedReport);
  }

  @Test
  public void testLoadFinalizedReports_reportThenMultipleEvents_returnsReportWithMultipleEvents() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);
    reportPersistence.persistEvent(testEvent2, sessionId);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    ArrayList<Event> events = new ArrayList<>();
    events.add(testEvent);
    events.add(testEvent2);
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(events),
        finalizedReport);
  }

  @Test
  public void
      testLoadFinalizedReports_reportsWithEventsInMultipleSessions_returnsReportsWithProperEvents() {
    final String sessionId1 = "testSession1";
    final CrashlyticsReport testReport1 = makeTestReport(sessionId1);
    final String sessionId2 = "testSession2";
    final CrashlyticsReport testReport2 = makeTestReport(sessionId2);
    final CrashlyticsReport.Session.Event testEvent1 = makeTestEvent();
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent();

    reportPersistence.persistReport(testReport1);
    reportPersistence.persistReport(testReport2);
    reportPersistence.persistEvent(testEvent1, sessionId1);
    reportPersistence.persistEvent(testEvent2, sessionId2);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(2, finalizedReports.size());
    final CrashlyticsReport finalizedReport1 = finalizedReports.get(1).getReport();
    assertEquals(
        testReport1
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(Collections.singletonList(testEvent1)),
        finalizedReport1);
    final CrashlyticsReport finalizedReport2 = finalizedReports.get(0).getReport();
    assertEquals(
        testReport2
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(Collections.singletonList(testEvent2)),
        finalizedReport2);
  }

  @Test
  public void testFinalizeReports_capsOpenSessions() throws IOException {
    for (int i = 0; i < 10; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + i, true);
    }

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(8, finalizedReports.size());
  }

  @Test
  public void testFinalizeReports_capsOldestSessionsFirst() throws IOException {
    DecimalFormat format = new DecimalFormat("00");
    for (int i = 0; i < 16; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + format.format(i), true);
    }

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(8, finalizedReports.size());

    List<String> reportIdentifiers = new ArrayList<>();
    for (CrashlyticsReportWithSessionId finalizedReport : finalizedReports) {
      reportIdentifiers.add(finalizedReport.getSessionId());
    }
    List<String> expectedSessions =
        Arrays.asList("testSession12", "testSession13", "testSession14", "testSession15");
    List<String> unexpectedSessions =
        Arrays.asList("testSession4", "testSession5", "testSession6", "testSession7");

    assertTrue(reportIdentifiers.containsAll(expectedSessions));
    for (String unexpectedSession : unexpectedSessions) {
      assertFalse(reportIdentifiers.contains(unexpectedSession));
    }
  }

  @Test
  public void testFinalizeReports_skipsCappingCurrentSession() throws IOException {
    for (int i = 0; i < 16; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + i, true);
    }

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("testSession5", endedAt);
    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(8, finalizedReports.size());
    persistReportWithEvent(reportPersistence, "testSession11", true);
    reportPersistence.finalizeReports("testSession11", endedAt);
    finalizedReports = reportPersistence.loadFinalizedReports();
    assertEquals(9, finalizedReports.size());
  }

  @Test
  public void testFinalizeReports_capsReports() {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(4, VERY_LARGE_UPPER_LIMIT),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));
    for (int i = 0; i < 10; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + i, true);
    }

    reportPersistence.finalizeReports("skippedSession", 0L);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(4, finalizedReports.size());
  }

  @Test
  public void testFinalizeReports_whenSettingsChanges_capsReports() throws IOException {
    SettingsProvider settingsProvider = mock(SettingsProvider.class);

    Settings.SessionData sessionData1 = new Settings.SessionData(VERY_LARGE_UPPER_LIMIT, 4);
    Settings.SessionData sessionData2 = new Settings.SessionData(VERY_LARGE_UPPER_LIMIT, 8);

    Settings settings1 =
        new Settings(0, sessionData1, new FeatureFlagData(true, true, false), 3, 0, 1.0, 1.0, 1);
    Settings settings2 =
        new Settings(0, sessionData2, new FeatureFlagData(true, true, false), 3, 0, 1.0, 1.0, 1);

    when(settingsProvider.getSettingsSync()).thenReturn(settings1);
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore, settingsProvider, createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));

    DecimalFormat format = new DecimalFormat("00");
    for (int i = 0; i < 16; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + format.format(i), true);
    }

    reportPersistence.finalizeReports("skippedSession", 0L);
    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(4, finalizedReports.size());
    when(settingsProvider.getSettingsSync()).thenReturn(settings2);

    for (int i = 16; i < 32; i++) {
      persistReportWithEvent(reportPersistence, "testSession" + i, true);
    }

    reportPersistence.finalizeReports("skippedSession", 0L);

    finalizedReports = reportPersistence.loadFinalizedReports();
    assertEquals(8, finalizedReports.size());
  }

  @Test
  public void testFinalizeReports_removesLowPriorityReportsFirst() throws IOException {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(4, VERY_LARGE_UPPER_LIMIT),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));

    for (int i = 0; i < 10; i++) {
      boolean priority = i >= 3 && i <= 8;
      String sessionId = "testSession" + i + (priority ? "high" : "low");
      persistReportWithEvent(reportPersistence, sessionId, priority);
    }

    reportPersistence.finalizeReports("skippedSession", 0L);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(4, finalizedReports.size());
    for (CrashlyticsReportWithSessionId finalizedReport : finalizedReports) {
      assertTrue(finalizedReport.getReport().getSession().getIdentifier().contains("high"));
    }
  }

  @Test
  public void testFinalizeReports_prioritizesNativeAndNonnativeFatals() throws IOException {

    CrashlyticsReport.FilesPayload filesPayload = makeFilePayload();
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(4, VERY_LARGE_UPPER_LIMIT),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));

    persistReportWithEvent(reportPersistence, "testSession1", true);
    reportPersistence.finalizeSessionWithNativeEvent("testSession1", filesPayload, null);
    persistReportWithEvent(reportPersistence, "testSession2low", false);
    persistReportWithEvent(reportPersistence, "testSession3low", false);
    persistReportWithEvent(reportPersistence, "testSession4", true);
    reportPersistence.finalizeSessionWithNativeEvent("testSession4", filesPayload, null);
    reportPersistence.finalizeReports("skippedSession", 0L);

    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    Set<String> reportNames = new HashSet<>();
    for (CrashlyticsReportWithSessionId finalizedReport : finalizedReports) {
      reportNames.add(finalizedReport.getSessionId());
    }
    assertEquals(Sets.newSet("testSession1", "testSession4"), reportNames);
    assertEquals(4, finalizedReports.size());
  }

  @Test
  public void testFinalizeReports_removesOldestReportsFirst() throws IOException {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(4, VERY_LARGE_UPPER_LIMIT),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));
    for (int i = 0; i < 8; i++) {
      String sessionId = "testSession" + i;
      persistReportWithEvent(reportPersistence, sessionId, true);
    }

    reportPersistence.finalizeReports("skippedSession", 0L);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(4, finalizedReports.size());
    List<String> reportIdentifiers = new ArrayList<>();
    for (CrashlyticsReportWithSessionId finalizedReport : finalizedReports) {
      reportIdentifiers.add(finalizedReport.getSessionId());
    }

    List<String> expectedSessions =
        Arrays.asList("testSession4", "testSession5", "testSession6", "testSession7");
    List<String> unexpectedSessions =
        Arrays.asList("testSession0", "testSession1", "testSession2", "testSession3");

    assertTrue(reportIdentifiers.containsAll(expectedSessions));
    for (String unexpectedSession : unexpectedSessions) {
      assertFalse(reportIdentifiers.contains(unexpectedSession));
    }
  }

  @Test
  public void testLoadFinalizedReports_reportWithUserId_returnsReportWithProperUserId() {
    final String sessionId = "testSession";
    final String userId = "testUser";
    final CrashlyticsReport testReport = makeTestReport(sessionId, userId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);
    reportPersistence.finalizeReports("skippedSession", 0L);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertNotNull(finalizedReport.getSession().getUser());
    assertEquals(userId, finalizedReport.getSession().getUser().getIdentifier());
  }

  @Test
  public void testLoadFinalizedReports_reportWithProcessDetails_returnsReportWithProcessDetails() {
    String sessionId = "testSession";
    CrashlyticsReport testReport = makeTestReport(sessionId);
    ProcessDetails process1 = makeProcessDetails("process1");
    ProcessDetails process2 = makeProcessDetails("process2");
    ArrayList<ProcessDetails> processDetails = new ArrayList<>();
    processDetails.add(process1);
    processDetails.add(process2);
    CrashlyticsReport.Session.Event testEvent =
        makeTestEvent("java.lang.Exception", "reason", process1, processDetails);

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);
    reportPersistence.finalizeReports(null, 0L);

    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();

    assertThat(finalizedReports).hasSize(1);
    CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertThat(finalizedReport.getSession()).isNotNull();
    assertThat(finalizedReport.getSession().getEvents()).isNotNull();
    Event event = finalizedReport.getSession().getEvents().get(0);
    assertThat(event.getApp().getCurrentProcessDetails()).isEqualTo(process1);
    assertThat(event.getApp().getAppProcessDetails()).containsExactly(process1, process2);
  }

  @Test
  public void
      testLoadFinalizedReports_reportsWithUserIdInMultipleSessions_returnsReportsWithProperUserIds() {
    final String userId1 = "testUser1";
    final String userId2 = "testUser2";
    final String sessionId1 = "testSession1";
    final String sessionId2 = "testSession2";
    final CrashlyticsReport testReport1 = makeTestReport(sessionId1, userId1);
    final CrashlyticsReport testReport2 = makeTestReport(sessionId2, userId2);
    final CrashlyticsReport.Session.Event testEvent1 = makeTestEvent();
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent();

    reportPersistence.persistReport(testReport1);
    reportPersistence.persistReport(testReport2);
    reportPersistence.persistEvent(testEvent1, sessionId1);
    reportPersistence.persistEvent(testEvent2, sessionId2);

    reportPersistence.finalizeReports("skippedSession", 0L);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(2, finalizedReports.size());
    final CrashlyticsReportWithSessionId finalizedReport1 = finalizedReports.get(1);
    assertNotNull(finalizedReport1.getReport().getSession().getUser());
    assertEquals(userId1, finalizedReport1.getReport().getSession().getUser().getIdentifier());
    final CrashlyticsReportWithSessionId finalizedReport2 = finalizedReports.get(0);
    assertNotNull(finalizedReport2.getReport().getSession().getUser());
    assertEquals(userId2, finalizedReport2.getReport().getSession().getUser().getIdentifier());
  }

  @Test
  public void testFinalizeSessionWithNativeEvent_writesNativeSessions() {
    final CrashlyticsReport testReport = makeTestReport("sessionId");
    reportPersistence.persistReport(testReport);
    CrashlyticsReport.FilesPayload filesPayload = makeFilePayload();
    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();

    assertEquals(0, finalizedReports.size());

    reportPersistence.finalizeSessionWithNativeEvent("sessionId", filesPayload, null);

    finalizedReports = reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    assertEquals(filesPayload, finalizedReports.get(0).getReport().getNdkPayload());
  }

  @Test
  public void testDeleteFinalizedReport_removesReports() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);

    reportPersistence.finalizeReports("skippedSession", 0L);

    assertEquals(1, reportPersistence.loadFinalizedReports().size());

    fileStore.getReport(sessionId).delete();

    assertEquals(0, reportPersistence.loadFinalizedReports().size());
  }

  @Test
  public void testDeleteFinalizedReport_withWrongSessionId_doesNotRemoveReports() {
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);

    reportPersistence.finalizeReports("skippedSession", 0L);

    assertEquals(1, reportPersistence.loadFinalizedReports().size());

    fileStore.getReport("wrongSessionId").delete();

    assertEquals(1, reportPersistence.loadFinalizedReports().size());
  }

  @Test
  public void testDeleteAllReports_removesAllReports() {
    final String sessionId1 = "testSession1";
    final CrashlyticsReport testReport1 = makeTestReport(sessionId1);
    final String sessionId2 = "testSession2";
    final CrashlyticsReport testReport2 = makeTestReport(sessionId2);
    final CrashlyticsReport.Session.Event testEvent1 = makeTestEvent();
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent();

    reportPersistence.persistReport(testReport1);
    reportPersistence.persistReport(testReport2);
    reportPersistence.persistEvent(testEvent1, sessionId1);
    reportPersistence.persistEvent(testEvent2, sessionId2);

    reportPersistence.finalizeReports("skippedSession", 0L);

    assertEquals(2, reportPersistence.loadFinalizedReports().size());

    reportPersistence.deleteAllReports();

    assertEquals(0, reportPersistence.loadFinalizedReports().size());
  }

  @Test
  public void testPersistEvent_keepsAppropriateNumberOfMostRecentEvents() throws IOException {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(VERY_LARGE_UPPER_LIMIT, 4),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent1 = makeTestEvent("type1", "reason1");
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent("type2", "reason2");
    final CrashlyticsReport.Session.Event testEvent3 = makeTestEvent("type3", "reason3");
    final CrashlyticsReport.Session.Event testEvent4 = makeTestEvent("type4", "reason4");
    final CrashlyticsReport.Session.Event testEvent5 = makeTestEvent("type5", "reason5");

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent1, sessionId);
    reportPersistence.persistEvent(testEvent2, sessionId);
    reportPersistence.persistEvent(testEvent3, sessionId);
    reportPersistence.persistEvent(testEvent4, sessionId);
    reportPersistence.persistEvent(testEvent5, sessionId);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertEquals(4, finalizedReport.getSession().getEvents().size());
    ArrayList<Event> events = new ArrayList<>();
    events.add(testEvent2);
    events.add(testEvent3);
    events.add(testEvent4);
    events.add(testEvent5);
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(events),
        finalizedReport);
  }

  @Test
  public void testPersistEvent_whenSettingsChanges_keepsAppropriateNumberOfMostRecentEvents()
      throws IOException {
    SettingsProvider settingsProvider = mock(SettingsProvider.class);
    Settings.SessionData sessionData1 = new Settings.SessionData(4, VERY_LARGE_UPPER_LIMIT);
    Settings.SessionData sessionData2 = new Settings.SessionData(8, VERY_LARGE_UPPER_LIMIT);

    Settings settings1 =
        new Settings(0, sessionData1, new FeatureFlagData(true, true, false), 3, 0, 1.0, 1.0, 1);
    Settings settings2 =
        new Settings(0, sessionData2, new FeatureFlagData(true, true, false), 3, 0, 1.0, 1.0, 1);

    when(settingsProvider.getSettingsSync()).thenReturn(settings1);
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore, settingsProvider, createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));

    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final CrashlyticsReport.Session.Event testEvent1 = makeTestEvent("type1", "reason1");
    final CrashlyticsReport.Session.Event testEvent2 = makeTestEvent("type2", "reason2");
    final CrashlyticsReport.Session.Event testEvent3 = makeTestEvent("type3", "reason3");
    final CrashlyticsReport.Session.Event testEvent4 = makeTestEvent("type4", "reason4");
    final CrashlyticsReport.Session.Event testEvent5 = makeTestEvent("type5", "reason5");

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent1, sessionId);
    reportPersistence.persistEvent(testEvent2, sessionId);
    reportPersistence.persistEvent(testEvent3, sessionId);
    reportPersistence.persistEvent(testEvent4, sessionId);
    reportPersistence.persistEvent(testEvent5, sessionId);

    long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertEquals(4, finalizedReport.getSession().getEvents().size());
    ArrayList<Event> events = new ArrayList<>();
    events.add(testEvent2);
    events.add(testEvent3);
    events.add(testEvent4);
    events.add(testEvent5);
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(events),
        finalizedReport);

    when(settingsProvider.getSettingsSync()).thenReturn(settings2);

    final CrashlyticsReport.Session.Event testEvent6 = makeTestEvent("type6", "reason6");
    final CrashlyticsReport.Session.Event testEvent7 = makeTestEvent("type7", "reason7");
    final CrashlyticsReport.Session.Event testEvent8 = makeTestEvent("type8", "reason8");
    final CrashlyticsReport.Session.Event testEvent9 = makeTestEvent("type9", "reason9");
    final CrashlyticsReport.Session.Event testEvent10 = makeTestEvent("type10", "reason10");

    final String sessionId2 = "testSession2";
    final CrashlyticsReport testReport2 = makeTestReport(sessionId2);
    reportPersistence.persistReport(testReport2);
    reportPersistence.persistEvent(testEvent1, sessionId2);
    reportPersistence.persistEvent(testEvent2, sessionId2);
    reportPersistence.persistEvent(testEvent3, sessionId2);
    reportPersistence.persistEvent(testEvent4, sessionId2);
    reportPersistence.persistEvent(testEvent5, sessionId2);
    reportPersistence.persistEvent(testEvent6, sessionId2);
    reportPersistence.persistEvent(testEvent7, sessionId2);
    reportPersistence.persistEvent(testEvent8, sessionId2);
    reportPersistence.persistEvent(testEvent9, sessionId2);
    reportPersistence.persistEvent(testEvent10, sessionId2);

    endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports2 =
        reportPersistence.loadFinalizedReports();
    assertEquals(2, finalizedReports2.size());
    final CrashlyticsReport finalizedReport2 = finalizedReports2.get(0).getReport();
    assertEquals(8, finalizedReport2.getSession().getEvents().size());
    ArrayList<Event> allEvents = new ArrayList<>();
    allEvents.add(testEvent3);
    allEvents.add(testEvent4);
    allEvents.add(testEvent5);
    allEvents.add(testEvent6);
    allEvents.add(testEvent7);
    allEvents.add(testEvent8);
    allEvents.add(testEvent9);
    allEvents.add(testEvent10);

    assertEquals(
        testReport2
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(APP_QUALITY_SESSION_ID)
            .withEvents(allEvents),
        finalizedReport2);
  }

  @Test
  public void testPersistReportWithAnrEvent() throws IOException {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(VERY_LARGE_UPPER_LIMIT, 4),
            createSessionsSubscriberMock(APP_QUALITY_SESSION_ID));
    final String sessionId = "testSession";
    final CrashlyticsReport testReport = makeTestReport(sessionId);
    final Event testEvent = makeTestAnrEvent();
    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId, true);

    final long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    final List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    final CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertEquals(1, finalizedReport.getSession().getEvents().size());
  }

  @Test
  public void testFinalizeReports_missingAppQualitySessionId() {
    reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(4, VERY_LARGE_UPPER_LIMIT),
            // Simulate Sessions subscriber failure by setting appQualitySessionId to null.
            createSessionsSubscriberMock(/* appQualitySessionId= */ null));

    String sessionId = "testSession";
    CrashlyticsReport testReport = makeTestReport(sessionId);
    CrashlyticsReport.Session.Event testEvent = makeTestEvent();

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent, sessionId);

    long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertNotNull(finalizedReport.getSession());
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withEvents(Collections.singletonList(testEvent)),
        finalizedReport);

    // getAppQualitySessionId should return null since sessions subscriber never got an id.
    assertNull(finalizedReport.getSession().getAppQualitySessionId());
  }

  @Test
  public void testPersistEvent_updatesLatestAppQualitySession() {
    CrashlyticsAppQualitySessionsSubscriber mockSessionsSubscriber =
        createSessionsSubscriberMock(APP_QUALITY_SESSION_ID);
    CrashlyticsReportPersistence reportPersistence =
        new CrashlyticsReportPersistence(
            fileStore,
            createSettingsProviderMock(VERY_LARGE_UPPER_LIMIT, VERY_LARGE_UPPER_LIMIT),
            mockSessionsSubscriber);

    String sessionId = "testSession";
    CrashlyticsReport testReport = makeTestReport(sessionId);
    CrashlyticsReport.Session.Event testEvent1 = makeTestEvent("type1", "reason1");
    CrashlyticsReport.Session.Event testEvent2 = makeTestEvent("type2", "reason2");
    CrashlyticsReport.Session.Event testEvent3 = makeTestEvent("type3", "reason3");

    reportPersistence.persistReport(testReport);
    reportPersistence.persistEvent(testEvent1, sessionId);
    reportPersistence.persistEvent(testEvent2, sessionId);

    // Simulate a new app quality sessions session before the last event.
    String latestAppQualitySessionId = "300";
    when(mockSessionsSubscriber.getAppQualitySessionId(anyString()))
        .thenReturn(latestAppQualitySessionId);
    reportPersistence.persistEvent(testEvent3, sessionId);

    long endedAt = System.currentTimeMillis();

    reportPersistence.finalizeReports("skippedSession", endedAt);

    List<CrashlyticsReportWithSessionId> finalizedReports =
        reportPersistence.loadFinalizedReports();
    assertEquals(1, finalizedReports.size());
    CrashlyticsReport finalizedReport = finalizedReports.get(0).getReport();
    assertNotNull(finalizedReport.getSession());
    ArrayList<Event> events = new ArrayList<>();
    events.add(testEvent1);
    events.add(testEvent2);
    events.add(testEvent3);
    assertEquals(
        testReport
            .withSessionEndFields(endedAt, false, null)
            .withAppQualitySessionId(latestAppQualitySessionId)
            .withEvents(events),
        finalizedReport);
  }

  private static void persistReportWithEvent(
      CrashlyticsReportPersistence reportPersistence, String sessionId, boolean isHighPriority) {
    CrashlyticsReport testReport = makeTestReport(sessionId);
    reportPersistence.persistReport(testReport);
    final CrashlyticsReport.Session.Event testEvent = makeTestEvent();
    reportPersistence.persistEvent(testEvent, sessionId, isHighPriority);
  }

  private static CrashlyticsReport.Builder makeIncompleteReport() {
    return CrashlyticsReport.builder()
        .setSdkVersion("sdkVersion")
        .setGmpAppId("gmpAppId")
        .setPlatform(1)
        .setInstallationUuid("installationId")
        .setFirebaseInstallationId("firebaseInstallationId")
        .setFirebaseAuthenticationToken("firebaseAuthenticationToken")
        .setBuildVersion("1")
        .setDisplayVersion("1.0.0");
  }

  private static CrashlyticsReport makeTestReport(String sessionId) {
    return makeIncompleteReport().setSession(makeTestSession(sessionId)).build();
  }

  private static CrashlyticsReport makeTestReport(String sessionId, String userId) {
    return makeTestReport(sessionId).withSessionEndFields(0L, false, userId);
  }

  private static CrashlyticsReport.FilesPayload makeFilePayload() {
    byte[] testContents = {0, 2, 20, 10};
    return CrashlyticsReport.FilesPayload.builder()
        .setOrgId("orgId")
        .setFiles(
            Collections.singletonList(
                CrashlyticsReport.FilesPayload.File.builder()
                    .setContents(testContents)
                    .setFilename("bytes")
                    .build()))
        .build();
  }

  private static CrashlyticsReport makeTestNativeReport() {
    byte[] testContents = {0, 2, 20, 10};
    CrashlyticsReport.FilesPayload filesPayload =
        CrashlyticsReport.FilesPayload.builder()
            .setOrgId("orgId")
            .setFiles(
                Collections.singletonList(
                    CrashlyticsReport.FilesPayload.File.builder()
                        .setContents(testContents)
                        .setFilename("bytes")
                        .build()))
            .build();

    return makeIncompleteReport().setNdkPayload(filesPayload).build();
  }

  private static CrashlyticsReport.Session makeTestSession(String sessionId) {
    return Session.builder()
        .setGenerator("generator")
        .setIdentifier(sessionId)
        .setStartedAt(0)
        .setApp(makeTestApplication())
        .setGeneratorType(3)
        .build();
  }

  private static Application makeTestApplication() {
    return Application.builder()
        .setIdentifier("applicationId")
        .setVersion("version")
        .setDisplayVersion("displayVersion")
        .build();
  }

  private static Event makeTestEvent() {
    return makeTestEvent("java.lang.Exception", "reason");
  }

  private static Event makeTestEvent(String type, String reason) {
    return makeTestEvent(
        type, reason, /* currentProcessDetails= */ null, /* appProcessDetails= */ null);
  }

  private static Event makeTestEvent(
      String type,
      String reason,
      @Nullable ProcessDetails currentProcessDetails,
      @Nullable List<ProcessDetails> appProcessDetails) {
    return Event.builder()
        .setType(type)
        .setTimestamp(1000)
        .setApp(
            Session.Event.Application.builder()
                .setBackground(false)
                .setCurrentProcessDetails(currentProcessDetails)
                .setAppProcessDetails(appProcessDetails)
                .setExecution(
                    Execution.builder()
                        .setBinaries(
                            Collections.singletonList(
                                Execution.BinaryImage.builder()
                                    .setBaseAddress(0)
                                    .setName("name")
                                    .setSize(100000)
                                    .setUuid("uuid")
                                    .build()))
                        .setException(
                            Execution.Exception.builder()
                                .setFrames(makeTestFrames())
                                .setOverflowCount(0)
                                .setReason(reason)
                                .setType(type)
                                .build())
                        .setSignal(Signal.builder().setCode("0").setName("0").setAddress(0).build())
                        .setThreads(
                            Collections.singletonList(
                                Session.Event.Application.Execution.Thread.builder()
                                    .setName("name")
                                    .setImportance(4)
                                    .setFrames(makeTestFrames())
                                    .build()))
                        .build())
                .setUiOrientation(1)
                .build())
        .setDevice(
            Session.Event.Device.builder()
                .setBatteryLevel(0.5)
                .setBatteryVelocity(3)
                .setDiskUsed(10000000)
                .setOrientation(1)
                .setProximityOn(true)
                .setRamUsed(10000000)
                .build())
        .build();
  }

  private static Event makeTestAnrEvent() {
    return Event.builder()
        .setType("anr")
        .setTimestamp(1000)
        .setApp(
            Session.Event.Application.builder()
                .setBackground(false)
                .setExecution(
                    Execution.builder()
                        .setBinaries(
                            Collections.singletonList(
                                Execution.BinaryImage.builder()
                                    .setBaseAddress(0)
                                    .setName("name")
                                    .setSize(100000)
                                    .setUuid("uuid")
                                    .build()))
                        .setSignal(Signal.builder().setCode("0").setName("0").setAddress(0).build())
                        .setAppExitInfo(makeAppExitInfo())
                        .build())
                .setUiOrientation(1)
                .build())
        .setDevice(
            Session.Event.Device.builder()
                .setBatteryLevel(0.5)
                .setBatteryVelocity(3)
                .setDiskUsed(10000000)
                .setOrientation(1)
                .setProximityOn(true)
                .setRamUsed(10000000)
                .build())
        .build();
  }

  private static List<Frame> makeTestFrames() {
    ArrayList<Frame> l = new ArrayList<>();
    l.add(
        Frame.builder()
            .setPc(0)
            .setSymbol("func1")
            .setFile("Test.java")
            .setOffset(36)
            .setImportance(4)
            .build());
    l.add(
        Frame.builder()
            .setPc(1)
            .setSymbol("func2")
            .setFile("Test.java")
            .setOffset(5637)
            .setImportance(4)
            .build());
    l.add(
        Frame.builder()
            .setPc(2)
            .setSymbol("func3")
            .setFile("Test.java")
            .setOffset(22429)
            .setImportance(4)
            .build());
    l.add(
        Frame.builder()
            .setPc(3)
            .setSymbol("func4")
            .setFile("Test.java")
            .setOffset(751)
            .setImportance(4)
            .build());

    return l;
  }

  private static CrashlyticsReport.ApplicationExitInfo makeAppExitInfo() {
    return CrashlyticsReport.ApplicationExitInfo.builder()
        .setTraceFile("trace")
        .setTimestamp(1L)
        .setImportance(1)
        .setReasonCode(1)
        .setProcessName("test")
        .setPid(1)
        .setPss(1L)
        .setRss(1L)
        .build();
  }

  private static ProcessDetails makeProcessDetails(String processName) {
    return ProcessDetails.builder()
        .setProcessName(processName)
        .setPid(0)
        .setImportance(0)
        .setDefaultProcess(false)
        .build();
  }
}
