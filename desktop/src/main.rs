#![windows_subsystem = "windows"]

slint::include_modules!();

use std::rc::Rc;
use slint::{Model, VecModel};

fn main() -> Result<(), slint::PlatformError> {
    let window = MainWindow::new()?;

    // Sample initial subscriptions
    let sub_items = vec![
        SubscriptionItem {
            id: "sub_1".into(),
            name: "Локальные профили".into(),
            count: 0,
            active: true,
        }
    ];
    let sub_model = Rc::new(VecModel::from(sub_items));
    window.set_subscriptions(sub_model.into());

    let server_items: Vec<ServerItem> = vec![];
    let server_model = Rc::new(VecModel::from(server_items));
    window.set_servers(server_model.into());

    // Connect toggle callback
    let w_weak = window.as_weak();
    window.on_connect_toggled(move || {
        let Some(w) = w_weak.upgrade() else { return };
        let is_connected = w.get_connected();
        if is_connected {
            w.set_connected(false);
            w.set_connecting(false);
            w.set_status_text("Отключено · Туннель неактивен".into());
        } else {
            w.set_connecting(true);
            w.set_status_text("Запуск ядра sing-box…".into());

            let w_weak_inner = w.as_weak();
            slint::Timer::single_shot(std::time::Duration::from_millis(800), move || {
                if let Some(inner) = w_weak_inner.upgrade() {
                    inner.set_connecting(false);
                    inner.set_connected(true);
                    inner.set_status_text("Защищено · TUN активен · sing-box".into());
                }
            });
        }
    });

    // Server selection callback
    let w_weak2 = window.as_weak();
    window.on_select_server(move |server_id| {
        let Some(w) = w_weak2.upgrade() else { return };
        w.set_selected_server_name(server_id);
        w.set_selected_server_meta("VLESS · reality · 443".into());
    });

    // Ping all callback
    let w_weak3 = window.as_weak();
    window.on_ping_all(move || {
        let Some(_w) = w_weak3.upgrade() else { return };
        // Trigger ping in background
    });

    // Open import dialog callback
    window.on_open_import(move || {
        // Open modal
    });

    // Open settings dialog callback
    window.on_open_settings(move || {
        // Open settings modal
    });

    window.run()
}
