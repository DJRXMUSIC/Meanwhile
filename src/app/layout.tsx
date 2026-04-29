import type { Metadata, Viewport } from "next";
import "./globals.css";
import { ServiceWorkerRegister } from "@/components/ServiceWorkerRegister";
import { TopBar } from "@/components/TopBar";

export const metadata: Metadata = {
  title: "Meanwhile",
  description: "AI decision-support harness for Type 1 Diabetes.",
  manifest: "/manifest.webmanifest",
  applicationName: "Meanwhile",
  appleWebApp: { capable: true, statusBarStyle: "black-translucent", title: "Meanwhile" },
  icons: { icon: "/icon-192.svg", apple: "/icon-192.svg" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  themeColor: "#0a0a0c",
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh bg-bg text-ink antialiased">
        <ServiceWorkerRegister />
        <div
          className="mx-auto flex min-h-dvh max-w-xl flex-col overflow-x-hidden"
          style={{
            paddingLeft: "env(safe-area-inset-left)",
            paddingRight: "env(safe-area-inset-right)",
          }}
        >
          <TopBar />
          <main className="flex-1 min-w-0">{children}</main>
        </div>
      </body>
    </html>
  );
}
