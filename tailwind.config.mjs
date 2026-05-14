/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{astro,html,js,jsx,ts,tsx,md,mdx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        bg: {
          DEFAULT: '#0a0d18',
          deep: '#05070f',
          panel: 'rgba(18, 22, 38, 0.65)',
          surface: 'rgba(28, 33, 52, 0.55)',
        },
        ink: {
          DEFAULT: '#e8ecf8',
          muted: '#9aa3bd',
          dim: '#6b7290',
        },
        brand: {
          50:  '#eef2ff',
          100: '#dde6ff',
          200: '#b9c8ff',
          300: '#8ca3ff',
          400: '#5d7bff',
          500: '#3a5bff',
          600: '#2a45e6',
          700: '#1f33b8',
          800: '#1a2a8f',
          900: '#16236f',
        },
        accent: {
          violet: '#8b5cf6',
          cyan:   '#22d3ee',
          mint:   '#34d399',
          rose:   '#fb7185',
          amber:  '#fbbf24',
        },
        line: {
          DEFAULT: 'rgba(255, 255, 255, 0.08)',
          strong:  'rgba(255, 255, 255, 0.16)',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
        display: ['"Sora"', 'Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      fontSize: {
        '2xs': ['0.6875rem', { lineHeight: '1rem' }],
      },
      backgroundImage: {
        'mesh-hero': 'radial-gradient(60% 80% at 50% 0%, rgba(58,91,255,0.35) 0%, rgba(58,91,255,0) 60%), radial-gradient(45% 60% at 20% 40%, rgba(139,92,246,0.35) 0%, rgba(139,92,246,0) 60%), radial-gradient(50% 60% at 90% 20%, rgba(34,211,238,0.25) 0%, rgba(34,211,238,0) 60%)',
        'mesh-app':  'radial-gradient(40% 60% at 10% -10%, rgba(58,91,255,0.18) 0%, rgba(58,91,255,0) 60%), radial-gradient(35% 50% at 90% 10%, rgba(139,92,246,0.16) 0%, rgba(139,92,246,0) 60%)',
        'brand-grad': 'linear-gradient(135deg, #3a5bff 0%, #8b5cf6 50%, #22d3ee 100%)',
      },
      boxShadow: {
        glass: '0 8px 32px 0 rgba(0, 0, 0, 0.36), inset 0 1px 0 rgba(255,255,255,0.04)',
        ring: '0 0 0 1px rgba(255,255,255,0.06), 0 8px 30px rgba(0,0,0,0.35)',
        glow: '0 0 0 1px rgba(58,91,255,0.35), 0 12px 40px rgba(58,91,255,0.25)',
      },
      borderRadius: {
        xl2: '1.125rem',
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: 0, transform: 'translateY(6px)' },
          '100%': { opacity: 1, transform: 'translateY(0)' },
        },
        'pulse-glow': {
          '0%, 100%': { boxShadow: '0 0 0 0 rgba(58,91,255,0.4)' },
          '50%': { boxShadow: '0 0 0 14px rgba(58,91,255,0)' },
        },
        'shimmer': {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'float': {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-6px)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 280ms ease-out both',
        'pulse-glow': 'pulse-glow 2.4s ease-in-out infinite',
        'shimmer': 'shimmer 2.2s linear infinite',
        'float': 'float 6s ease-in-out infinite',
      },
      backdropBlur: {
        xs: '2px',
      },
      transitionTimingFunction: {
        spring: 'cubic-bezier(0.22, 1, 0.36, 1)',
      },
    },
  },
  plugins: [],
};
