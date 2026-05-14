import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';
import node from '@astrojs/node';

// https://astro.build/config
export default defineConfig({
  output: 'server',
  adapter: node({ mode: 'standalone' }),
  site: 'https://hopepms.example.com',
  server: { port: 4321, host: true },
  integrations: [
    tailwind({ applyBaseStyles: false }),
  ],
  vite: {
    ssr: { noExternal: ['@auth0/auth0-spa-js'] },
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
