import {defineConfig} from 'vite'
import {svelte} from '@sveltejs/vite-plugin-svelte'

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [svelte()],
    base: "",
    // Allow the sandbox's proxied preview hostname in addition to Vite's localhost defaults.
    server: { allowedHosts: [".e2b.app"] },
    css: {
        preprocessorOptions: {
            scss: {
                api: "modern"
            }
        }
    }
})
