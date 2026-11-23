import type { Metadata } from "next";
import Link from "next/link";
import { Geist, Geist_Mono } from "next/font/google";
import { Toaster } from "@/components/ui/sonner";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "AI RestClient",
  description: "Swagger를 AI로 분석해 Spring RestClient를 자동 생성하고 통신 테스트까지 수행합니다.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className={`${geistSans.variable} ${geistMono.variable} antialiased`}>
        <header className="border-b">
          <div className="mx-auto flex h-14 max-w-6xl items-center px-4">
            <Link href="/" className="font-semibold">
              AI RestClient
            </Link>
            <span className="ml-3 hidden text-sm text-muted-foreground sm:inline">
              Swagger → RestClient 자동 생성 · 통신 테스트
            </span>
          </div>
        </header>
        <main className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-6">{children}</main>
        <Toaster />
      </body>
    </html>
  );
}
