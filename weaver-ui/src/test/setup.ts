import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import '@testing-library/jest-dom/vitest';

// Testing Library 仅在框架暴露全局 Hook 时自动清理；本项目运行 Vitest 时未开启 globals，
// 因此测试之间需要显式卸载。
afterEach(() => cleanup());
