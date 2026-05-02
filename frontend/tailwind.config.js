/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        primary:  { DEFAULT: '#1a56db', 50: '#eff6ff', 500: '#1a56db', 700: '#1e429f' },
        surface:  { DEFAULT: '#ffffff', dark: '#1e2433' },
        sidebar:  { DEFAULT: '#f9fafb', dark: '#111827' },
      },
      fontFamily: { sans: ['Inter', 'system-ui', 'sans-serif'] },
      animation: {
        'pulse-slow': 'pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'typing': 'typing 1.4s steps(3,end) infinite',
      }
    }
  },
  plugins: []
}
