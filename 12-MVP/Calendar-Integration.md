# Calendar Integration

Status: **PROPOSED — not built.** No code in this repository implements any of it yet.

Like `12-MVP/MVP-Scope.md`, this document sits outside the frozen POC scope. It records a decision
about shape before the work starts, because the shape is the whole point: this is wanted in more
than one project, so the thing to build is a service that speaks calendars, not a feature that
speaks shifts.

That second project now has a name and a shape, and both constrain what is written here.
`12-MVP/Clara-Care-Integration.md` records it, and `09-Decisions/AUTODEC-0008-Clara-Care-Integration-Boundary.md`
turns the parts that bind this document into decisions. Two of them change what follows outright:
the read side takes free/busy rather than events, and the mirror stores windows rather than words.

## What it is for

Two different jobs, and they are worth naming separately because only one of them is obvious.

**Writing.** When a shift is claimed, it should appear in her Google Calendar. That is the request
as stated, and it is the easy half: one event, created after the claim is confirmed.

**Reading.** Her calendar already knows what she is doing on the 3rd — appointments, another
hospital's shift, a holiday she booked. The agenda conflict rule built in `WP-MVP-001` reads
commitments from a port with several sources. A calendar is the source that would make that rule
correct without her retyping her life into `EP-041`.

Reading is the more valuable half and the one that changes the product. It is listed second because
writing is the one that can ship first.

## Where it plugs in

The seam already exists, which is the reason to write this down now rather than later.

```text
rules -> availability -> CommitmentSourcePort
                         ├── ManualCommitmentSource        (availability_entry)
                         ├── ClaimedShiftCommitmentSource  (shift_claim, read live)
                         └── CalendarCommitmentSource      (this document)
```

`AvailabilityService` merges every `CommitmentSourcePort` bean Spring hands it and never names one.
A calendar adapter becomes a third bean. The rule engine does not change, `AvailabilityService` does
not change, and the merged view at `EP-040` starts showing a third `source`. That is the entire
integration on the read side.

The write side has an equally obvious seam and a less obvious rule attached to it. The claim is
delivered through the outbox (`DEC-006`): the claim state and the *intent* to send are one
transaction, the send is not. A calendar write is the same kind of thing — an external effect that
can fail, retry and be refused — so it belongs behind the same outbox with its own event type, not
inline in `ClaimService`.

The lesson from `EP-037` applies with full force here: **the sequence must not be one transaction.**
The Google call happens outside the database, so wrapping claim-plus-calendar-write in a transaction
would mean that reporting a failed calendar write rolls back the record of the claim that really was
sent. Individual writes transactional; the whole not, because the whole cannot be.

A failed calendar write must never fail the claim. She took the shift; the calendar not knowing
about it is an inconvenience, and losing the claim over it would be a disaster.

## What "generic" has to mean

The other project needs this too, so the boundary matters more than the implementation.

The service should know about **events, calendars and credentials**. It should not know what a
shift is, what a claim is, or what `PEGO` means. Concretely, the port it exposes is roughly:

```text
CalendarPort
  busyWindows(calendarId, from, to): List<BusyWindow>
  listEvents(calendarId, from, to, syncToken?): EventPage
  createEvent(calendarId, CalendarEvent): CalendarEventRef
  updateEvent(ref, CalendarEvent): CalendarEventRef
  deleteEvent(ref)
  watch(calendarId, callbackUrl): ChannelRef
  renewWatch(ChannelRef): ChannelRef
  stopWatch(ChannelRef)
  connect(consentGrant): CredentialRef
  refresh(CredentialRef): CredentialRef
  revoke(CredentialRef)
```

The first four operations were the original proposal, and they are the shape a shift-claim *writer*
needs. The rest are what the second consumer cannot work without, and each answers something Clara
Care already has in code or in its baseline: `busyWindows` because the read side must never receive
titles; `syncToken` because re-reading a window on a schedule neither scales nor notices a deletion;
the watch trio because its webhook ingress already carries a channel route with `resourceId`, token
hash and expiry; and the credential trio because a refresh token is hers, expires, and can be
revoked — which the port has to be able to say out loud, not merely fail at.

`CalendarEvent` carries a time window, a title, a location, a description and
`extendedProperties.private` holding both an external correlation key and a **`source` marker**.
The marker is not decoration. Two systems writing into one calendar without it means this one reads
the shift it just created as an external commitment conflicting with itself, and the other reads a
shift as an unexplained external change. One field, two symmetric bugs that would both present as
data problems rather than design ones.

`BusyWindow` carries a start, an end and nothing else — that is the entire point of it.

The time zone travels inside the event rather than being read from configuration. This project pins
`America/Sao_Paulo` in `ShiftCatcherProperties`; Clara Care resolves a zone per tenant and
practitioner. A shared library that reads its host's config is not shared. Shift Catcher's job is to translate a `ShiftClaim` into
that shape and a `CalendarEvent` into a `Commitment`; the calendar service's job is to talk to
Google. Two adapters, one on each side of a boundary that neither crosses.

Whether it ends up a shared library, a separate service, or a copied package is a decision for when
the second project actually needs it. What must be true either way is that the domain translation
lives here and the Google specifics live there. Getting that line right costs nothing now and is
expensive to fix later.

## What makes this harder than it looks

**Credentials, again.** This is the same gravity as the WhatsApp instance in `MVP-Scope.md`: a
Google OAuth refresh token is *her* credential, not ours. It needs a consent flow, storage encrypted
at rest, refresh handling, and a revocation path. With one user it is a row; with several it is the
same per-user credential problem, which is another reason both belong to the same milestone rather
than being solved twice.

Scope should be the narrowest that works — a single calendar's events, ideally a dedicated one — so
that a token leak exposes shifts rather than her whole life.

**Latency.** Reaction speed is the product. The conflict rule currently runs a local query in
milliseconds; a Google API call is an order of magnitude slower and can time out. So the calendar
must **not** be read synchronously on the evaluation path. It should be mirrored into local rows on
a schedule and read from there, which means `CalendarCommitmentSource` queries a mirror table rather
than the network.

That is a real difference from `ClaimedShiftCommitmentSource`, which is deliberately *not* mirrored,
and the reason is worth being explicit about: claims are local, so reading them live is both cheap
and always correct. Calendar events are remote, so live reads would put a network call on the fast
path. A mirror is the right answer for the remote source and the wrong answer for the local one, and
the mirror brings a staleness window that has to be accepted and bounded — a shift booked elsewhere
five minutes ago may not be visible yet.

**Idempotency.** A retried outbox event must not create a second calendar entry. The claim id is the
natural external key.

**Retraction.** `EP-037` takes a claim back. The calendar event has to go with it, and deleting a
remote event has exactly the failure mode the retraction code already handles: the provider can
refuse, and the record must survive the report of that refusal.

**LGPD, and the leak a dedicated calendar does not close.** Reading her calendar means storing
third parties' appointments — the same category of exposure `MVP-Scope.md` already flags for group
messages, applied to a more intimate source. A dedicated calendar limits that, and a retention
policy is not optional at this point.

But a dedicated calendar limits the exposure to *her* private life, and there is a second one it
does nothing about. Clara Care writes its appointments into her calendar, and those events are
titled with a **patient's name**. Mirroring events into local rows — exactly what this document
specifies, for good latency reasons — would move patient data into a database whose
`01-Product/Non-Goals.md` forbids it, through a seam designed for something else, without anybody
deciding to. Hence the two rules from `AUTODEC-0008`: the adapter asks for **free/busy rather than
events**, so the words never arrive; and the **mirror schema has no column for a title**, so no
later code path can put one there. Both are free before the adapter is written and archaeology
afterwards.

## Where it belongs in the order

`MVP-Scope.md` recommends: close `WP-POC-008`; configurable message and agenda rule (now done);
a screen for one operator; multi-tenancy.

Calendar writing fits naturally alongside the screen — it is small, self-contained, and visible.
Calendar reading depends on per-user credentials and so shares a milestone with multi-tenancy, since
both need the same OAuth-token-as-data machinery.

Doing the write half first is worth it on its own: it delivers something she feels immediately, and
it builds the credential handling that the read half needs, on the smaller and less sensitive scope.
