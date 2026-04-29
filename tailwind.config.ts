import type { Config } from "tailwindcss";

export default {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        bg: "#0a0a0c",
        surface: "#15151a",
        surface2: "#1f1f26",
        accent: "#7c5cff",
        good: "#3ddc97",
        warn: "#ffb84d",
        bad: "#ff5c7c",
        muted: "#8a8a99",
        ink: "#f5f5f7",
      },
      fontFamily: {
        sans: ["system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
      },
    },
  },
  plugins: [],
} satisfies Config;
