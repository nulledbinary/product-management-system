import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';

// HopePMS frontend: static prerender hosted on Amplify. All dynamic concerns
// (auth, sessions, data) live in the Spring Boot backend on ECS, fronted by
// CloudFront and reached from the browser via the Amplify /api/* rewrite rule.
//
// https://astro.build/config
export default defineConfig({
  output: 'static',
  site: 'https://hopepms.example.com',
  server: { port: 4321, host: true },
  integrations: [
    tailwind({ applyBaseStyles: false }),
  ],
  vite: {
    // Dev-only: proxy /api/* to the Spring Boot backend running on localhost.
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: false,
        },
      },
    },
    build: {
      cssMinify: 'lightningcss',
      rollupOptions: {
        output: {
          manualChunks: {
            auth: ['@auth0/auth0-spa-js'],
          },
        },
      },
    },
  },
  security: {
    checkOrigin: true,
  },
  compressHTML: true,
  prefetch: { defaultStrategy: 'viewport' },
});
