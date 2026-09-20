import { cn } from "@/lib/utils";
import { Loader2 } from "lucide-react";
import Link from "next/link";
import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "outline" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

const variantClasses: Record<Variant, string> = {
  primary:
    "bg-lemon text-black hover:bg-lemon-dim disabled:bg-lemon/50",
  secondary:
    "bg-white text-black hover:bg-white/90 disabled:bg-white/50",
  outline:
    "border border-border-strong text-text hover:border-lemon hover:text-lemon bg-transparent",
  ghost: "text-text-muted hover:text-text hover:bg-white/5",
  danger: "bg-red-500 text-white hover:bg-red-400",
};

const sizeClasses: Record<Size, string> = {
  sm: "h-8 px-3 text-xs gap-1.5",
  md: "h-10 px-4 text-sm gap-2",
  lg: "h-12 px-6 text-sm gap-2",
};

type BaseProps = {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  className?: string;
};

type ButtonAsButton = BaseProps &
  ButtonHTMLAttributes<HTMLButtonElement> & { href?: undefined };

type ButtonAsLink = BaseProps & {
  href: string;
  children?: React.ReactNode;
  target?: string;
  rel?: string;
};

export function Button(props: ButtonAsButton | ButtonAsLink) {
  const { variant = "primary", size = "md", loading, className, children, ...rest } =
    props;

  const classes = cn(
    "inline-flex items-center justify-center whitespace-nowrap rounded-full font-semibold uppercase tracking-wide transition-colors disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-lemon focus-visible:ring-offset-2 focus-visible:ring-offset-black",
    variantClasses[variant],
    sizeClasses[size],
    className
  );

  if ("href" in props && props.href) {
    const { href, target, rel } = props as ButtonAsLink;
    return (
      <Link href={href} target={target} rel={rel} className={classes}>
        {children}
      </Link>
    );
  }

  const buttonProps = rest as ButtonHTMLAttributes<HTMLButtonElement>;
  return (
    <button className={classes} disabled={loading || buttonProps.disabled} {...buttonProps}>
      {loading && <Loader2 className="size-4 animate-spin" />}
      {children}
    </button>
  );
}
