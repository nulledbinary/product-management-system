import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';
import node from '@astrojs/node';

// SSR — Astro runs on AWS Amplify compute. The frontend never talks to the
// database directly; all data calls go to the Spring Boot backend at
// PUBLIC_API_BASE_URL (set per Amplify environment, e.g. https://api.hopepms.example.com).
export default defineConfig({
  output: 'server',
  adapter: node({ mode: 'standalone' }),
  site: 'https://hopepms.example.com',
  server: { port: 4321, host: true },
  integrations: [
    tailwind({ applyBaseStyles: false }),
  ],
  vite: {
    build: {
      cssMinify: 'lightningcss',
    },
  },
  security: {
    checkOrigin: true,
  },
  compressHTML: true,
  prefetch: { defaultStrategy: 'viewport' },
});
