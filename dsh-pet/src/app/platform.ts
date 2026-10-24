/** Capabilities describe OS constraints to the UI without promising compositor features. */
export function platformCapabilities(platform = process.platform, env = process.env) {
  const wayland =
    platform === 'linux' &&
    env.DSH_PET_WINDOW_BACKEND !== 'x11' &&
    (env.XDG_SESSION_TYPE === 'wayland' || !!env.WAYLAND_DISPLAY) &&
    !process.argv.includes('--ozone-platform=x11');
  return {
    platform,
    session: platform === 'linux' ? (wayland ? 'wayland' : 'x11') : 'native',
    globalPositioning: !wayland,
    clickThrough: !wayland,
    notes: wayland ? ['Wayland 限制全局窗口定位和鼠标穿透；需要完整桌宠交互时请使用 X11 / XWayland 会话。'] : [],
  };
}
