import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import '@testing-library/jest-dom/vitest';

// Testing Library only auto-cleans when the framework exposes global hooks; the
// project runs Vitest without globals, so unmount explicitly between tests.
afterEach(() => cleanup());
