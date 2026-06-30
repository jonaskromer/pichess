// Browser surface: load the SPA and walk the tournament-HISTORY browse page,
// then drill into one archived tournament's ladder + game list. This validates
// the web-ui history view end-to-end through the gateway → repository archive
// (the data the Gatling/HTTP layer can't see rendered).
//
// Flow:
//   1. Open `/#history` — the history index (Main.scala Screen.History), which
//      fetches GET /tournament/history via the gateway.
//   2. Open `/#history/<tid>` — one tournament's ladder + games
//      (Screen.HistoryDetail), GET /tournament/archive/<tid>.
//
// Screenshots land in /out (k6-run bind-mounts perf-reports/<ts>/k6 → /out).

import { browser } from 'k6/browser';
import { check, sleep } from 'k6';
import { cfg } from '/k6lib/config.js';
import { browserThresholds } from '/k6lib/thresholds.js';

export const options = {
  scenarios: {
    history_flow: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: cfg.duration,
      options: { browser: { type: 'chromium' } },
    },
  },
  thresholds: browserThresholds,
};

// A finished tournament known to be archived (from the e2e validation run);
// override with HISTORY_TID / HISTORY_NAME for a different one.
const TID  = __ENV.HISTORY_TID  || '09cf4e3f';
const NAME = __ENV.HISTORY_NAME || 'e2e-archive';

export default async function () {
  const page = await browser.newPage();
  try {
    // 1. History index — fetches + renders the archived-tournament table.
    await page.goto(cfg.gatewayUrl + '/#history', { waitUntil: 'networkidle' });
    sleep(2); // let the async fetch + Laminar render settle
    await page.screenshot({ path: '/out/history-list.png' });
    const list = await page.content();
    check(list, {
      'history screen mounts (heading)': (h) => h.includes('Past tournaments'),
      'history list shows an archived tournament': (h) =>
        h.includes(NAME) || h.includes(TID),
    });

    // 2. Detail — the final ladder + the game list.
    await page.goto(cfg.gatewayUrl + '/#history/' + TID, {
      waitUntil: 'networkidle',
    });
    sleep(2);
    await page.screenshot({ path: '/out/history-detail.png' });
    const detail = await page.content();
    check(detail, {
      'detail mounts (Games heading)': (h) => h.includes('Games'),
      'ladder shows a competitor': (h) => /pichess|rnd-/.test(h),
      'ladder has the merged W/D/L column': (h) => h.includes('W/D/L'),
      'games have an Open board button': (h) => h.includes('Open board'),
    });

    // 3. Open a game in the board: click the LAST "Open board" (the decisive
    //    game → terminal → the end-of-game result card shows). It should land on
    //    the read-only spectator board, titled with the real bot names, and the
    //    result card should offer "Analyze game" (not "New Game").
    const openBtns = await page.$$('.col-action button');
    if (openBtns && openBtns.length > 0) {
      await openBtns[openBtns.length - 1].click();
      sleep(3); // create game from PGN + navigate(#watch) + load + render
      await page.screenshot({ path: '/out/history-board.png' });
      const board = await page.content();
      const titleEl = await page.$('.game-title');
      const titleTxt = titleEl ? await titleEl.textContent() : '(no .game-title)';
      const verdictEl = await page.$('.result-dialog');
      const verdictTxt = verdictEl
        ? await verdictEl.textContent()
        : '(no result card)';
      console.log('HEADER TITLE TEXT: [' + titleTxt + ']');
      console.log('RESULT CARD TEXT: [' + verdictTxt + ']');
      check(board, {
        'HEADER TITLE shows a participant bot name': () =>
          /pichess-arch|rnd-1/.test(titleTxt),
        'spectator card offers Analyze game (not New Game)': (h) =>
          h.includes('Analyze game') && !h.includes('New Game'),
        'verdict names the winner (not the colour)': () =>
          /pichess-arch/.test(verdictTxt),
      });

      // 4. RELOAD the board — the title must survive (it's derived from the
      //    archived game id in the URL, not a transient that's lost on reload).
      //    Use 'load' not 'networkidle' — the board holds an open SSE, so the
      //    network never goes idle.
      await page.reload({ waitUntil: 'load' });
      sleep(4);
      await page.screenshot({ path: '/out/history-board-reloaded.png' });
      const titleEl2 = await page.$('.game-title');
      const titleTxt2 = titleEl2 ? await titleEl2.textContent() : '(none)';
      console.log('HEADER TITLE AFTER RELOAD: [' + titleTxt2 + ']');
      check(titleTxt2, {
        'TITLE SURVIVES RELOAD (names tied to the archived id)': (t) =>
          /pichess-arch|rnd-1/.test(t),
      });
    }
  } finally {
    await page.close();
  }
}
