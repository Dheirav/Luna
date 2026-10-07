# How other period trackers look, and what Luna should take from them

8 Oct 2026. Written after the "Why these numbers?" redesign, to answer whether Luna's look holds up
against the apps people actually use, and where the "spark" should come from.

**A caveat first.** I could not open these apps. Everything below comes from design critiques,
reviews, showcase write-ups and the vendors' own help pages (sources at the end), some of them a few
years old. Each app's layout is described the way those sources describe it, not from a screen I
checked, so treat specifics as "how it is generally reported" rather than "how it looks today".

## What each app does

**Flo** (the biggest, 320M+ users). Today is built around a circular cycle diagram with a countdown
to the next period ("Period in 3 days") or the current period day. Pastel pink and blue, pink for
period days and blue for ovulation, which a critique credits for staying distinguishable to
colour-blind users. The criticism is consistent across sources: the home screen is cluttered with
articles and images that push the cycle statistics down, onboarding runs to about 70 screens, and
paywalls are everywhere. One redesign study surveyed users and found most preferred gentle pastels,
but wanted the cycle day and prediction in the centre with everything else moved away.

**Clue** (the "science" one). Also a cycle ring as the centrepiece, showing the cycle day with
colour shifting by phase, on a clean white background. Deliberately not pink: red, green, teal, grey
and a little purple, with small pictograms for predictions (blue fireworks for ovulation, grey clouds
for estimated PMS days). Logging is a set of categories with colourful icons, more than 100 options,
and custom tags. Their redesign rebuilt the colour system and calendar for accessibility and screen
readers. A reviewer summed up the appeal as "deflowered and de-kittened": attractive without being
cutesy.

**Stardust** (the "beautiful" one). Dark purple background, a glowing ring of moon phases on launch
showing where you are and when the next period is expected, custom icons throughout. The calendar
for logging a period uses moon phases instead of a grid. Logging is icon-first: tap a symptom icon
and a panel opens with a slider for intensity. Onboarding turns the birthday question into an
animated constellation picker. Reviews mostly praise the look ("a sucker for purple", "I love the
monthly wheel"), and the copy is playful ("Daily Decodes").

**Natural Cycles.** Today is a single coloured circle (green or red for the fertility status) with
the cycle day inside and the phase written underneath, plus a short strip of the coming days.

**Apple Health (Cycle Tracking).** A horizontal timeline of days. Solid red circles are logged period
days, red stripes are predicted ones, a light blue oval is the predicted fertile window, purple dots
are days with anything logged. Predictions only start after two logged periods.

**Drip and Euki** (the privacy ones, both offline like Luna). Drip deliberately avoids the "cute,
pink" look and is described as functional rather than polished. Euki is praised for privacy, not
looks. Neither is a design reference, which is the gap Luna can occupy: offline and private, and
also lovely.

## The patterns

1. **Everyone puts a ring at the centre of Today.** Flo, Clue, Stardust and Natural Cycles all lead
   with a circle. It works because a cycle is a loop, and a ring shows "where am I in it" faster than
   any sentence. Luna leads with words ("DAY 40 · Luteal · of a 28-day cycle") on a gradient card.
   This is the single biggest visual difference, and probably most of why it feels plainer.
2. **Logging is icon-led.** Clue, Stardust and Flo all give each category an icon. Luna's log form
   is text only: labels over rows of segmented buttons. It is fast, but it reads like a form.
3. **Predicted and logged look different.** Apple's solid vs striped is the same idea as Luna's solid
   vs dashed, and Clue uses pictograms for estimates. Luna is already on the right side of this.
4. **The look that gets praised has one strong idea.** Stardust's is the moon, Clue's is restraint.
   Flo's is pastel, but its clutter is what reviewers remember.
5. **Delight is in small moments**, like an animated picker, a glowing ring or a custom icon, and
   not in decoration spread everywhere.

## Where Luna already wins

- **Honesty.** Flo's headline is a countdown to one date, which is exactly the single-date claim
  Luna's spec forbids. Luna's window, its "was expected" state, and now the dots for logged versus
  estimated cycles are things none of these apps show as clearly.
- **No clutter.** No articles, no paywall, a two-page onboarding. The thing Flo is most criticised
  for is the thing Luna does not have.
- **A dark theme that is warm.** Stardust's dark purple is the most praised look in this set, and
  Luna's plum dark theme is already close to it.

## What I would change, in order of impact

1. **A cycle ring in the hero.** Keep the gradient card and the mascot, and add a ring that shows the
   cycle as a loop, today's position as a dot, and the expected window as a soft band on the ring,
   never a single tick. Two things have to be designed properly rather than copied:
   - *Late cycles.* At day 40 of a 28-day cycle a normal ring has nowhere to put today. The ring
     should close at the expected length, and days past it run on as a dotted tail outside the loop,
     so it is visible that this cycle has gone past what was expected without the ring pretending
     day 40 is day 12.
   - *Estimated vs observed.* Phases are worked out, not measured, so the arcs should be soft tints
     and any estimated segment dashed, the same language as the calendar.
2. ~~A rounded typeface for headings.~~ **Tried and rejected on 8 Oct.** Nunito was bundled and put
   on the phone, and the system face was preferred. (The build that was seen rendered Nunito at its
   ExtraLight default because of a weight bug, which was then fixed, but the decision was to keep
   the system face.) Do not propose it again; the spark has to come from elsewhere.
3. **Icons on the log form**, one per row label (a drop for bleeding, a bolt for energy, a moon for
   sleep, a small cloud for mood). Drawn in the same style as the existing sparkles and hearts.
   These label categories, so they do not break the "nothing decorative on the log screen" rule, but
   they must sit beside the words, never replace them.
4. **A moment after saving.** When you come back to Today from a save, the Logged today card could
   arrive with a small sparkle animation. Stardust and Flo both earn affection through moments like
   this, and it is the right place for one because it rewards the habit the app depends on.
5. **Warmer microcopy in the phase guide only**, borrowing Stardust's tone but not its claims. The
   phase guide is already separate from the logs, which is the line that has to hold: typical
   guidance can be playful, while what the person logged stays plain.

## What not to copy

- **A countdown to one date** (Flo). It breaks the window rule.
- **Articles or content on Today** (Flo). It is the main thing people complain about.
- **Moon or astrology framing** (Stardust). It is a strong idea, but it makes claims Luna would have
  to disown.
- **Streaks.** None of the sources mention them for these apps, and for a health log a broken streak
  is a reason to stop, which works against the logging habit.

The ring (1) is the structural change that makes Today look like a cycle tracker at a glance. With
the font ruled out, the icons (3) and the save moment (4) are what carry the feel to other screens.

## Sources

- [Design critique: Flo iOS app (Pratt IxD, 2025)](https://ixd.prattsi.org/2025/09/design-critique-flo-ios-app/)
- [How I redesigned Flo cycle tracking iOS app](https://julianova.substack.com/p/flo-app-redesign)
- [Flo's empathetic design (Raw Studio)](https://raw.studio/blog/flos-empathetic-design-hacks-crafting-data-driven-experiences-for-female-health-apps/)
- [Clue on ScreensDesign](https://screensdesign.com/apps/clue-period-cycle-tracker/)
- [Clue: period tracking relaunched and newly designed](https://helloclue.com/articles/how-to-use-clue/clue-period-tracking-relaunched-and-newly-designed)
- [Clue review, Android Police (2014)](https://www.androidpolice.com/2014/10/10/ladies-clue-pink-less-flower-less-butterfly-less-period-tracker-may-ashamed-using/)
- [How design helped Clue (Creative Review)](https://www.creativereview.co.uk/design-clue-inclusive-period-tracking-app/)
- [Decoding Stardust (ScreensDesign)](https://screensdesign.com/showcase/stardust-period-pregnancy)
- [Stardust review (Bustle)](https://www.bustle.com/wellness/stardust-app-review-period-tracking-astrology)
- [Stardust (Hypebae)](https://hypebae.com/2020/11/stardust-period-tracker-horoscope-astronomy-astrology-mobile-apps-menstrual-care-cycle)
- [Natural Cycles app tour](https://help.naturalcycles.com/hc/en-us/articles/9209631867933-How-to-make-the-most-of-the-app-App-tour)
- [Apple: Track your period with Cycle Tracking](https://support.apple.com/120356)
- [Best period tracker apps of 2026 (Bearable)](https://bearable.app/the-best-period-tracker-apps-of-2026/)
- [drip](https://dripapp.org/)
- [Period tracking apps and privacy (ExpressVPN)](https://www.expressvpn.com/blog/period-tracking-apps/)
