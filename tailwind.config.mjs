/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{astro,html,js,jsx,ts,tsx,md,mdx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Monochrome, Framer-grade surfaces. Near-black canvas, lifted panels.
        bg: {
          DEFAULT: '#0a0a0b',
          deep: '#000000',
          panel: 'rgba(255, 255, 255, 0.025)',
          surface: 'rgba(255, 255, 255, 0.045)',
        },
        ink: {
          DEFAULT: '#fafafa',
          muted: '#a1a1aa',
          dim: '#71717a',
        },
        // "brand" is intentionally neutral so every existing brand-* class
        // resolves to a clean grayscale tone instead of blue/violet.
        brand: {
          50:  '#fafafa',
          100: '#f4f4f5',
          200: '#e4e4e7',
          300: '#d4d4d8',
          400: '#a1a1aa',
          500: '#fafafa',
          600: '#e4e4e7',
          700: '#a1a1aa',
          800: '#52525b',
          900: '#27272a',
        },
        accent: {
          violet: '#e4e4e7',
          cyan:   '#d4d4d8',
          mint:   '#4ade80',
          rose:   '#fb7185',
          amber:  '#fbbf24',
        },
        line: {
          DEFAULT: 'rgba(255, 255, 255, 0.09)',
          strong:  'rgba(255, 255, 255, 0.18)',
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
        'mesh-hero':
          'radial-gradient(60% 80% at 50% 0%, rgba(255,255,255,0.10) 0%, rgba(255,255,255,0) 60%), radial-gradient(45% 60% at 18% 35%, rgba(255,255,255,0.06) 0%, rgba(255,255,255,0) 60%)',
        'mesh-app':
          'radial-gradient(40% 60% at 12% -10%, rgba(255,255,255,0.05) 0%, rgba(255,255,255,0) 60%)',
        'brand-grad': 'linear-gradient(135deg, #ffffff 0%, #d4d4d8 100%)',
        'sheen': 'linear-gradient(110deg, transparent 35%, rgba(255,255,255,0.45) 50%, transparent 65%)',
      },
      boxShadow: {
        glass: '0 1px 0 0 rgba(255,255,255,0.05) inset, 0 16px 48px -12px rgba(0,0,0,0.7)',
        ring: '0 0 0 1px rgba(255,255,255,0.10), 0 12px 36px -10px rgba(0,0,0,0.65)',
        glow: '0 0 0 1px rgba(255,255,255,0.22), 0 14px 44px -10px rgba(255,255,255,0.10)',
      },
      borderRadius: {
        xl2: '1.125rem',
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: 0, transform: 'translateY(8px)' },
          '100%': { opacity: 1, transform: 'translateY(0)' },
        },
        'rise': {
          '0%': { opacity: 0, transform: 'translateY(14px)' },
          '100%': { opacity: 1, transform: 'translateY(0)' },
        },
        'modal-in': {
          '0%': { opacity: 0, transform: 'translate(-50%, -46%) scale(0.96)' },
          '100%': { opacity: 1, transform: 'translate(-50%, -50%) scale(1)' },
        },
        'bar-grow': {
          '0%': { transform: 'scaleX(0)' },
          '100%': { transform: 'scaleX(1)' },
        },
        'pulse-glow': {
          '0%, 100%': { boxShadow: '0 0 0 0 rgba(255,255,255,0.22)' },
          '50%': { boxShadow: '0 0 0 12px rgba(255,255,255,0)' },
        },
        'shimmer': {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'sheen': {
          '0%': { backgroundPosition: '-150% 0' },
          '100%': { backgroundPosition: '150% 0' },
        },
        'float': {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-6px)' },
        },
        'dot': {
          '0%, 100%': { opacity: 0.35, transform: 'scale(0.85)' },
          '50%': { opacity: 1, transform: 'scale(1)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 320ms cubic-bezier(0.22,1,0.36,1) both',
        'rise': 'rise 560ms cubic-bezier(0.22,1,0.36,1) both',
        'modal-in': 'modal-in 220ms cubic-bezier(0.22,1,0.36,1) both',
        'bar-grow': 'bar-grow 760ms cubic-bezier(0.22,1,0.36,1) both',
        'pulse-glow': 'pulse-glow 2.6s ease-in-out infinite',
        'shimmer': 'shimmer 2.2s linear infinite',
        'sheen': 'sheen 2.8s ease-in-out infinite',
        'float': 'float 6s ease-in-out infinite',
        'dot': 'dot 1.6s ease-in-out infinite',
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
