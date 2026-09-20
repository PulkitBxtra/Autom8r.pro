"use client";

import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Card } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Avatar } from "@/components/ui/avatar";
import { useAuth } from "@/lib/auth-context";

export default function SettingsPage() {
  const { user, logout } = useAuth();
  const router = useRouter();

  return (
    <>
      <Topbar title="Settings" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-lg">
          <Card className="p-6">
            <div className="flex items-center gap-4">
              <Avatar label={user?.email ?? "?"} className="size-14 text-base" />
              <div className="min-w-0">
                <p className="truncate text-base font-bold">{user?.email}</p>
                <p className="truncate text-xs text-text-faint">{user?.id}</p>
              </div>
            </div>

            <div className="mt-6">
              <Label>Email</Label>
              <p className="text-sm text-text">{user?.email}</p>
            </div>

            <div className="mt-8 border-t border-border pt-6">
              <Button
                variant="outline"
                onClick={async () => {
                  await logout();
                  router.push("/login");
                }}
              >
                <LogOut className="size-4" />
                Log out
              </Button>
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
