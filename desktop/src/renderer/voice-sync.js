/**
 * voice-sync.js — Renderer-side voice sync UI controller.
 *
 * This file is loaded via <script> tag in index.html alongside
 * script-editor.js. It manages the voice sync panel UI state and
 * coordinates with the main process through the preload bridge.
 *
 * Responsibilities:
 *   • Start/Stop voice sync buttons
 *   • Display partial recognition results
 *   • Show scroll position and match confidence bars
 *   • Handle manual scroll override (pause voice sync when user drags)
 *
 * NOTE: Most of the voice sync UI event wiring is done in script-editor.js
 * since it needs access to the connection state. This file provides
 * the VoiceSyncUI controller class for organizing the voice sync state
 * and a few helper functions.
 */

(function () {
  'use strict';

  /**
   * VoiceSyncUI — manages the voice sync panel state.
   */
  class VoiceSyncUI {
    constructor() {
      this.isRunning = false;
      this.lastPartialText = '';
      this.lastScrollPercent = 0;
      this.lastConfidence = 0;

      // DOM elements
      this.startBtn = document.getElementById('voice-start-btn');
      this.stopBtn = document.getElementById('voice-stop-btn');
      this.statusText = document.getElementById('voice-status-text');
      this.partialTextEl = document.getElementById('partial-text');
      this.scrollBar = document.getElementById('scroll-bar');
      this.scrollPercent = document.getElementById('scroll-percent');
      this.confidenceBar = document.getElementById('confidence-bar');
      this.confidencePercent = document.getElementById('confidence-percent');
      this.manualScrollSlider = document.getElementById('manual-scroll-slider');
    }

    /**
     * Update the UI to reflect that voice sync is running.
     */
    setRunning() {
      this.isRunning = true;
      this.startBtn.disabled = true;
      this.stopBtn.disabled = false;
      this.statusText.textContent = 'Listening…';
      this.statusText.classList.add('listening');
    }

    /**
     * Update the UI to reflect that voice sync has stopped.
     */
    setStopped() {
      this.isRunning = false;
      this.startBtn.disabled = false;
      this.stopBtn.disabled = true;
      this.statusText.textContent = 'Idle';
      this.statusText.classList.remove('listening');
    }

    /**
     * Update the partial recognition text display.
     * @param {string} text
     */
    updatePartial(text) {
      this.lastPartialText = text || '';
      this.partialTextEl.textContent = this.lastPartialText || '—';

      // Add a subtle pulse animation when text updates
      this.partialTextEl.classList.remove('pulse');
      void this.partialTextEl.offsetWidth; // force reflow
      this.partialTextEl.classList.add('pulse');
    }

    /**
     * Update the scroll position bar.
     * @param {number} percent — 0.0 to 1.0
     */
    updateScroll(percent) {
      this.lastScrollPercent = percent;
      const pct = Math.round(percent * 100);
      this.scrollBar.style.width = `${pct}%`;
      this.scrollPercent.textContent = `${pct}%`;
    }

    /**
     * Update the match confidence bar.
     * @param {number} confidence — 0.0 to 1.0
     */
    updateConfidence(confidence) {
      this.lastConfidence = confidence;
      const pct = Math.round(confidence * 100);
      this.confidenceBar.style.width = `${pct}%`;
      this.confidencePercent.textContent = `${pct}%`;

      // Color-code the confidence bar
      if (pct >= 70) {
        this.confidenceBar.className = 'confidence-bar high';
      } else if (pct >= 40) {
        this.confidenceBar.className = 'confidence-bar medium';
      } else {
        this.confidenceBar.className = 'confidence-bar low';
      }
    }

    /**
     * Reset all displays to their default state.
     */
    reset() {
      this.updatePartial('');
      this.updateScroll(0);
      this.updateConfidence(0);
      this.setStopped();
    }
  }

  // Expose globally so script-editor.js can access it
  window.VoiceSyncUI = VoiceSyncUI;

  // Auto-initialize on DOMContentLoaded
  document.addEventListener('DOMContentLoaded', () => {
    window.voiceSyncUI = new VoiceSyncUI();
  });
})();
