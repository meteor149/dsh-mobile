(() => {
  'use strict'

  const MOBILE_QUERY = '(max-width: 767px)'
  const COLUMN_ROLES = ['sidebar', 'center', 'details']
  const media = window.matchMedia(MOBILE_QUERY)
  let frame = null
  let frameObserver = null
  let scheduled = false
  let swipeStart = null

  const scheduleSync = () => {
    if (scheduled) return
    scheduled = true
    requestAnimationFrame(() => {
      scheduled = false
      sync()
    })
  }

  const sidebarColumn = () => frame?.querySelector(':scope > [data-dsh-mobile-role="sidebar"]') ?? null

  const sidebarToggle = () => {
    const sidebar = sidebarColumn()
    if (sidebar === null) return null
    return [...sidebar.querySelectorAll('button[aria-label]')].find(button => {
      const label = button.getAttribute('aria-label') ?? ''
      return /sidebar|侧边栏/iu.test(label)
    }) ?? null
  }

  const nativeHeaderToggleVisible = () => {
    const sidebar = sidebarColumn()
    return [...document.querySelectorAll('button[aria-label]:not([data-dsh-mobile-ui-menu])')].some(button => {
      if (sidebar?.contains(button)) return false
      const label = button.getAttribute('aria-label') ?? ''
      return /sidebar|侧边栏/iu.test(label) && button.getClientRects().length > 0
    })
  }

  const toggleSidebar = open => {
    if (frame === null) return
    const isOpen = !frame.hasAttribute('data-sidebar-collapsed')
    if (open === isOpen) return
    const toggle = sidebarToggle()
    if (toggle !== null) {
      toggle.click()
      return
    }
    frame.toggleAttribute('data-sidebar-collapsed', !open)
    scheduleSync()
  }

  const ensureControls = () => {
    if (document.body === null) return
    let menu = document.querySelector('[data-dsh-mobile-ui-menu]')
    if (menu === null) {
      menu = document.createElement('button')
      menu.type = 'button'
      menu.setAttribute('data-dsh-mobile-ui-menu', '')
      menu.setAttribute('aria-label', document.documentElement.lang.startsWith('zh') ? '打开侧边栏' : 'Open sidebar')
      menu.innerHTML = '<svg aria-hidden="true" width="20" height="20" viewBox="0 0 20 20" fill="none"><rect x="2.5" y="3" width="15" height="14" rx="2.5" stroke="currentColor" stroke-width="1.5"/><path d="M7 3.75v12.5" stroke="currentColor" stroke-width="1.5"/></svg>'
      menu.addEventListener('click', () => toggleSidebar(true))
      document.body.append(menu)
    }

    let scrim = document.querySelector('[data-dsh-mobile-ui-scrim]')
    if (scrim === null) {
      scrim = document.createElement('button')
      scrim.type = 'button'
      scrim.setAttribute('data-dsh-mobile-ui-scrim', '')
      scrim.setAttribute('aria-label', document.documentElement.lang.startsWith('zh') ? '关闭侧边栏' : 'Close sidebar')
      scrim.addEventListener('click', () => toggleSidebar(false))
      document.body.append(scrim)
    }
  }

  const stampPhoneControls = () => {
    const sessionLog = [...document.querySelectorAll('button[aria-busy]')]
      .find(button => /^(Session log|会话日志)$/u.test(button.textContent?.trim() ?? ''))
    sessionLog?.setAttribute('data-session-log-download', '')

    const accessMode = [...document.querySelectorAll('button[aria-label]')].find(button => {
      const label = button.getAttribute('aria-label') ?? ''
      return label.startsWith('Access mode') || label.startsWith('访问模式')
    })
    if (accessMode !== undefined) {
      accessMode.setAttribute('data-input-access-mode', '')
      const modes = accessMode.parentElement?.parentElement
      const tools = modes?.parentElement
      const toolbar = tools?.parentElement
      const trailing = toolbar?.lastElementChild
      if (modes && tools && toolbar && trailing && trailing !== tools) {
        modes.setAttribute('data-composer-modes', '')
        tools.setAttribute('data-composer-tools', '')
        toolbar.setAttribute('data-composer-toolbar', '')
        trailing.setAttribute('data-composer-trailing', '')
      }
    }

    document.querySelectorAll('textarea').forEach(textarea => {
      textarea.setAttribute('enterkeyhint', 'send')
    })
  }

  const stampSettingsPage = () => {
    document.querySelectorAll('[data-dsh-mobile-settings-open]').forEach(sidebar => {
      sidebar.removeAttribute('data-dsh-mobile-settings-open')
    })
    const dialog = [...document.querySelectorAll('[role="dialog"]')].find(candidate =>
      candidate.querySelector('[data-slot="settings.header"]') !== null,
    )
    if (dialog === undefined) return

    dialog.setAttribute('data-dsh-mobile-settings', '')
    dialog.parentElement?.setAttribute('data-dsh-mobile-settings-overlay', '')
    dialog.closest('[data-dsh-mobile-role="sidebar"]')
      ?.setAttribute('data-dsh-mobile-settings-open', '')
    const nav = dialog.querySelector(':scope > nav')
    const content = nav?.nextElementSibling ?? null
    const header = content?.firstElementChild ?? null
    const options = header?.nextElementSibling ?? null
    nav?.setAttribute('data-dsh-mobile-settings-nav', '')
    nav?.firstElementChild?.setAttribute('data-dsh-mobile-settings-title', '')
    nav?.children[1]?.setAttribute('data-dsh-mobile-settings-tabs', '')
    content?.setAttribute('data-dsh-mobile-settings-content', '')
    header?.setAttribute('data-dsh-mobile-settings-header', '')
    options?.setAttribute('data-dsh-mobile-settings-options', '')

    const sectionSlot = dialog.querySelector('[data-slot="settings.section"]')
    const section = sectionSlot?.firstElementChild ?? null
    if (section === null) return
    section.setAttribute('data-dsh-mobile-settings-section', '')

    const blocks = [...section.children].flatMap(child =>
      getComputedStyle(child).display === 'contents' ? [...child.children] : [child],
    )
    blocks.forEach(block => {
      if (!(block instanceof HTMLElement)) return
      const rowAttribute = 'data-dsh-mobile-settings-row'
      const groupAttribute = 'data-dsh-mobile-settings-group'
      let isRow = block.hasAttribute(rowAttribute)
      if (isRow) {
        block.removeAttribute(groupAttribute)
      } else if (!block.hasAttribute(groupAttribute)) {
        isRow = getComputedStyle(block).flexDirection === 'row'
        block.setAttribute(isRow ? rowAttribute : groupAttribute, '')
      }
      if (isRow && block.firstElementChild !== block.lastElementChild) {
        block.firstElementChild?.setAttribute('data-dsh-mobile-settings-copy', '')
        block.lastElementChild?.setAttribute('data-dsh-mobile-settings-control', '')
      }
      if (!isRow) {
        const choiceGrid = [...block.children].find(child =>
          child.querySelectorAll(':scope > button').length > 1,
        )
        choiceGrid?.setAttribute('data-dsh-mobile-settings-choice-grid', '')
      }
    })
  }

  const attachFrame = nextFrame => {
    if (nextFrame === frame) return
    frameObserver?.disconnect()
    frame = nextFrame
    if (frame === null) return

    frame.setAttribute('data-dsh-mobile-frame', '')
    const overlay = frame.querySelector(':scope > [data-shell-overlay]')
    let roleIndex = 0
    for (const child of frame.children) {
      if (child === overlay || roleIndex >= COLUMN_ROLES.length) break
      child.setAttribute('data-dsh-mobile-role', COLUMN_ROLES[roleIndex])
      roleIndex += 1
    }

    frameObserver = new MutationObserver(scheduleSync)
    frameObserver.observe(frame, {
      attributes: true,
      attributeFilter: ['data-sidebar-collapsed', 'data-details-collapsed'],
    })
  }

  const syncControls = () => {
    ensureControls()
    const menu = document.querySelector('[data-dsh-mobile-ui-menu]')
    const scrim = document.querySelector('[data-dsh-mobile-ui-scrim]')
    const mobile = media.matches
    const sidebarOpen = frame !== null && !frame.hasAttribute('data-sidebar-collapsed')
    if (menu !== null) {
      const center = frame?.querySelector(':scope > [data-dsh-mobile-role="center"]') ?? null
      const titleRow = center?.querySelector('[data-phase] > header:not([aria-hidden="true"])')?.firstElementChild ?? null
      if (mobile && titleRow !== null) {
        titleRow.setAttribute('data-dsh-mobile-title-row', '')
        if (menu.parentElement !== titleRow || menu !== titleRow.lastElementChild) titleRow.append(menu)
        menu.setAttribute('data-dsh-mobile-ui-menu-inline', '')
      } else {
        if (menu.parentElement !== document.body) document.body.append(menu)
        menu.removeAttribute('data-dsh-mobile-ui-menu-inline')
      }
      const shouldHideMenu = !mobile || sidebarOpen || nativeHeaderToggleVisible()
      if (menu.hidden !== shouldHideMenu) menu.hidden = shouldHideMenu
    }
    if (scrim !== null) scrim.hidden = !mobile || !sidebarOpen
  }

  const sync = () => {
    const overlay = document.querySelector('[data-shell-overlay]')
    attachFrame(overlay?.parentElement ?? null)
    stampPhoneControls()
    stampSettingsPage()
    syncControls()
  }

  const onTouchStart = event => {
    const touch = event.touches[0]
    swipeStart = touch !== undefined &&
      event.touches.length === 1 &&
      media.matches &&
      frame?.hasAttribute('data-sidebar-collapsed') &&
      touch.clientX <= 24
      ? { x: touch.clientX, y: touch.clientY }
      : null
  }

  const onTouchMove = event => {
    if (swipeStart === null || event.touches.length !== 1) return
    const touch = event.touches[0]
    if (touch === undefined) return
    const deltaX = touch.clientX - swipeStart.x
    const deltaY = Math.abs(touch.clientY - swipeStart.y)
    if (deltaX < 0 || deltaY > 32) {
      swipeStart = null
      return
    }
    if (deltaX >= 56) {
      swipeStart = null
      toggleSidebar(true)
    }
  }

  const onSidebarClick = event => {
    if (!media.matches || frame?.hasAttribute('data-sidebar-collapsed')) return
    const target = event.target
    if (!(target instanceof Element)) return

    const sidebar = sidebarColumn()
    if (sidebar === null || !sidebar.contains(target)) return

    const button = target.closest('button[aria-label]')
    const buttonLabel = button?.getAttribute('aria-label') ?? ''
    if (button !== null && /new session|新建会话/iu.test(buttonLabel)) {
      requestAnimationFrame(() => toggleSidebar(false))
      return
    }

    const session = target.closest('[role="treeitem"][aria-selected]')
    if (session === null) return
    if (session.getAttribute('aria-selected') === 'true') return
    if (target.closest('button, a, input, select, textarea, [role="button"], [role="menuitem"]')) return

    requestAnimationFrame(() => toggleSidebar(false))
  }

  const start = () => {
    const bodyObserver = new MutationObserver(scheduleSync)
    bodyObserver.observe(document.body, { childList: true, subtree: true })
    media.addEventListener('change', scheduleSync)
    document.addEventListener('click', onSidebarClick, true)
    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchmove', onTouchMove, { passive: true })
    window.addEventListener('touchend', () => { swipeStart = null }, { passive: true })
    window.addEventListener('touchcancel', () => { swipeStart = null }, { passive: true })
    sync()
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true })
  } else {
    start()
  }
})()
