"use client";

import { useEffect } from "react";
import { startAutoBackup } from "@/lib/driveBackup";

// The daily cloud backup, running in the background while the app is open.
export function AutoBackup() {
  useEffect(() => startAutoBackup(), []);
  return null;
}
