import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
  configurable: true,
  value() {
    this.setAttribute('open', '');
  },
});
Object.defineProperty(HTMLDialogElement.prototype, 'close', {
  configurable: true,
  value() {
    this.removeAttribute('open');
  },
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
