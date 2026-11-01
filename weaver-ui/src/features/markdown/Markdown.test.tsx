import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';

describe('Markdown', () => {
  it('renders bold text', () => {
    render(<Markdown content="**粗体**" />);

    expect(screen.getByText('粗体').tagName).toBe('STRONG');
  });

  it('renders inline code', () => {
    render(<Markdown content="`x`" />);

    expect(screen.getByText('x').tagName).toBe('CODE');
  });

  it('renders fenced code blocks', () => {
    const { container } = render(<Markdown content={'```java\nint x = 1;\n```'} />);

    expect(container.querySelector('pre > code')).toHaveTextContent('int x = 1;');
  });

  it('renders unordered lists', () => {
    const { container } = render(<Markdown content={'- first\n- second'} />);

    expect(container.querySelectorAll('ul > li')).toHaveLength(2);
  });

  it('renders GFM tables', () => {
    const content = ['| Name | Value |', '| --- | --- |', '| A | 1 |'].join('\n');
    const { container } = render(<Markdown content={content} />);

    expect(container.querySelector('table')).toBeInTheDocument();
    expect(container.querySelector('th')).toHaveTextContent('Name');
  });

  it('opens allowed links in a new tab without a window opener', () => {
    render(<Markdown content="[a](https://example.com)" />);

    expect(screen.getByRole('link', { name: 'a' })).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: 'a' })).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('does not render an href for unsafe links', () => {
    render(<Markdown content="[x](javascript:alert(1))" />);

    const link = screen.getByText('x').closest('a');
    expect(link).not.toBeNull();
    expect(link).not.toHaveAttribute('href');
  });

  it('rejects protocols outside the link allowlist', () => {
    render(<Markdown content="[x](irc://example.com)" />);

    expect(screen.getByText('x').closest('a')).not.toHaveAttribute('href');
  });

  it('loads images lazily while preserving alternative text', () => {
    render(<Markdown content="![diagram](https://example.com/diagram.png)" />);

    expect(screen.getByRole('img', { name: 'diagram' })).toHaveAttribute('loading', 'lazy');
  });

  it('renders raw HTML as text without creating executable elements', () => {
    const { container } = render(<Markdown content="<script>alert(1)</script>" />);

    expect(container).toHaveTextContent('<script>alert(1)</script>');
    expect(container.querySelector('script')).toBeNull();
  });
});
