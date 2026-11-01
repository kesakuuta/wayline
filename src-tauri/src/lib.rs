mod core;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .manage(core::CoreState::default())
        .invoke_handler(tauri::generate_handler![
            core::core_connect,
            core::core_disconnect,
            core::core_status,
            core::core_log,
            core::core_ping,
            core::core_take_pending_connect,
            core::core_backup_directory,
            core::core_backup_list,
            core::core_backup_create,
            core::core_backup_restore,
            core::core_backup_delete,
            core::fetch_subscription
        ])
        .build(tauri::generate_context!())
        .expect("error building Wayline desktop application");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            core::stop_on_exit(app_handle);
        }
    });
}
